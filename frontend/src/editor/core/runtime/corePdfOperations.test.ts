import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { PDFDocument, PDFName, PDFPageTree, PDFArray } from "@cantoo/pdf-lib";
import {
  runCorePdfOperation,
  localPageSelection,
} from "@editor/runtime/corePdfOperations";

const arrayBufferDescriptor = Object.getOwnPropertyDescriptor(
  Blob.prototype,
  "arrayBuffer",
);
const fileArrayBufferDescriptor = Object.getOwnPropertyDescriptor(
  File.prototype,
  "arrayBuffer",
);
beforeAll(() => {
  const descriptor = {
    configurable: true,
    value: function (this: Blob) {
      return new Promise<ArrayBuffer>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result as ArrayBuffer);
        reader.onerror = () => reject(reader.error);
        reader.readAsArrayBuffer(this);
      });
    },
  };
  Object.defineProperty(Blob.prototype, "arrayBuffer", descriptor);
  Object.defineProperty(File.prototype, "arrayBuffer", descriptor);
});
afterAll(() => {
  if (arrayBufferDescriptor)
    Object.defineProperty(Blob.prototype, "arrayBuffer", arrayBufferDescriptor);
  else Reflect.deleteProperty(Blob.prototype, "arrayBuffer");
  if (fileArrayBufferDescriptor)
    Object.defineProperty(
      File.prototype,
      "arrayBuffer",
      fileArrayBufferDescriptor,
    );
  else Reflect.deleteProperty(File.prototype, "arrayBuffer");
});

async function fixture(options: { form?: boolean; outline?: boolean } = {}) {
  const doc = await PDFDocument.create();
  for (let i = 1; i <= 3; i++) {
    const page = doc.addPage([100 + i, 200 + i]);
    page.drawText(`Sootan page ${i}`);
  }
  if (options.form) {
    const field = doc.getForm().createTextField("name");
    field.setText("Sootan text");
    field.addToPage(doc.getPage(0));
  }
  if (options.outline) {
    const outline = doc.context.obj({ Type: "Outlines", Count: 1 });
    const outlineRef = doc.context.register(outline);
    const item = doc.context.register(
      doc.context.obj({
        Title: "First",
        Parent: outlineRef,
        Dest: [doc.getPage(0).ref, "Fit"],
      }),
    );
    outline.set(PDFName.of("First"), item);
    outline.set(PDFName.of("Last"), item);
    doc.catalog.set(PDFName.of("Outlines"), outlineRef);
    const root = doc.catalog.Pages(),
      rootRef = doc.catalog.get(PDFName.of("Pages"));
    const nested = PDFPageTree.withContext(doc.context);
    const nestedRef = doc.context.register(nested);
    nested.set(PDFName.of("Parent"), rootRef!);
    doc.getPages().forEach((page) => {
      nested.pushLeafNode(page.ref);
      page.node.setParent(nestedRef);
    });
    root.set(PDFName.of("Kids"), doc.context.obj([nestedRef]));
    root.set(PDFName.of("Count"), doc.context.obj(3));
  }
  const bytes = await doc.save();
  return new File([new Uint8Array(bytes).buffer], "fixture.pdf", {
    type: "application/pdf",
  });
}
async function load(file: File) {
  return PDFDocument.load(await file.arrayBuffer());
}
describe("local core PDF object operations", () => {
  it("merges, extracts and splits in page order without changing originals", async () => {
    const file = await fixture(),
      original = new Uint8Array(await file.arrayBuffer());
    const merged = await runCorePdfOperation({
      kind: "merge",
      files: [file, file],
    });
    expect((await load(merged.files[0])).getPageCount()).toBe(6);
    const extracted = await runCorePdfOperation({
      kind: "select",
      files: [file],
      pages: [3, 1],
    });
    expect(
      (await load(extracted.files[0])).getPages().map((p) => p.getWidth()),
    ).toEqual([103, 101]);
    const split = await runCorePdfOperation({
      kind: "split",
      files: [file],
      groups: [[1], [2, 3]],
    });
    expect(
      await Promise.all(
        split.files.map(async (f) => (await load(f)).getPageCount()),
      ),
    ).toEqual([1, 2]);
    expect(new Uint8Array(await file.arrayBuffer())).toEqual(original);
  });
  it("preserves form values and bookmark objects during rotation and full reordering", async () => {
    const file = await fixture({ form: true, outline: true });
    const rotated = await runCorePdfOperation({
      kind: "rotate",
      files: [file],
      angle: 90,
    });
    const doc = await load(rotated.files[0]);
    expect(doc.getPage(0).getRotation().angle).toBe(90);
    expect(doc.getForm().getTextField("name").getText()).toBe("Sootan text");
    expect(doc.catalog.has(PDFName.of("Outlines"))).toBe(true);
    const result = await runCorePdfOperation({
      kind: "select",
      files: [file],
      pages: [3, 1, 2],
    });
    const reordered = await load(result.files[0]);
    expect(reordered.getPages().map((p) => p.getWidth())).toEqual([
      103, 101, 102,
    ]);
    expect(reordered.getForm().getTextField("name").getText()).toBe(
      "Sootan text",
    );
    expect(reordered.catalog.has(PDFName.of("Outlines"))).toBe(true);
    const outline = reordered.catalog.lookup(PDFName.of("Outlines"));
    expect(outline).toBeTruthy();
    const first = reordered.context.lookup(
      (outline as import("@cantoo/pdf-lib").PDFDict).get(PDFName.of("First")),
    ) as import("@cantoo/pdf-lib").PDFDict;
    expect(first.lookup(PDFName.of("Dest"), PDFArray).get(0)).toEqual(
      reordered.getPage(1).ref,
    );
    expect(reordered.catalog.Pages().Count().asNumber()).toBe(3);
  });
  it("refuses preservation losses and invalid page/angle requests explicitly", async () => {
    const file = await fixture({ form: true, outline: true });
    await expect(
      runCorePdfOperation({ kind: "select", files: [file], pages: [1] }),
    ).rejects.toMatchObject({ code: "UNSUPPORTED_FEATURE" });
    await expect(
      runCorePdfOperation({ kind: "merge", files: [file, file] }),
    ).rejects.toMatchObject({ code: "UNSUPPORTED_FEATURE" });
    await expect(
      runCorePdfOperation({ kind: "rotate", files: [file], angle: 45 }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(() => localPageSelection("99", 3)).toThrow();
    expect(() => localPageSelection("1,invalid", 3)).toThrow();
  });
  it("composes the Multi Tool page plan with absolute rotations and no rasterization", async () => {
    const file = await fixture();
    const result = await runCorePdfOperation({
      kind: "compose",
      files: [file],
      pages: [
        { source: 0, page: 3, rotation: 180 },
        { source: 0, page: 1, rotation: 90 },
      ],
    });
    const doc = await load(result.files[0]);
    expect(doc.getPages().map((p) => p.getWidth())).toEqual([103, 101]);
    expect(doc.getPages().map((p) => p.getRotation().angle)).toEqual([180, 90]);
    expect(doc.getPage(0).node.has(PDFName.of("Contents"))).toBe(true);
  });
  it("honors cancellation and strict requested order", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(
      runCorePdfOperation(
        { kind: "merge", files: [] },
        { signal: controller.signal },
      ),
    ).rejects.toMatchObject({ code: "CANCELLED" });
    expect(localPageSelection("3-1,2", 3)).toEqual([3, 2, 1, 2]);
  });
  it("refuses encrypted and signature-bearing sources without changing their bytes", async () => {
    for (const protectedKind of ["encrypted", "signed"]) {
      const doc = await PDFDocument.create();
      doc.addPage();
      if (protectedKind === "encrypted")
        doc.encrypt({ userPassword: "fixture", ownerPassword: "owner" });
      else {
        const field = doc.context.register(
          doc.context.obj({ FT: "Sig", T: "signature" }),
        );
        doc.catalog.set(
          PDFName.of("AcroForm"),
          doc.context.obj({ Fields: [field] }),
        );
      }
      const bytes = new Uint8Array(await doc.save());
      const source = new File([bytes.buffer], `${protectedKind}.pdf`, {
        type: "application/pdf",
      });
      await expect(
        runCorePdfOperation({ kind: "rotate", files: [source], angle: 90 }),
      ).rejects.toMatchObject({ code: "UNSUPPORTED_FEATURE" });
      expect(new Uint8Array(await source.arrayBuffer())).toEqual(bytes);
    }
  });
});
