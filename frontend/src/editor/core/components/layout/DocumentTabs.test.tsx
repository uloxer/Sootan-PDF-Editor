import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
const mocks = vi.hoisted(() => ({
  active: "a" as string | null,
  setActive: vi.fn(),
  remove: vi.fn(),
  selected: vi.fn(),
  navigate: vi.fn(),
  guard: vi.fn(),
  blocked: false,
}));
vi.mock("@editor/ui/Icon", () => ({ Icon: () => null }));
vi.mock("@editor/contexts/FileContext", () => ({
  useAllFiles: () => ({
    files: ["a", "b", "c"].map((fileId) => ({ fileId, name: `${fileId}.pdf` })),
  }),
  useFileActions: () => ({
    actions: { removeFiles: mocks.remove, setSelectedFiles: mocks.selected },
  }),
}));
vi.mock("@editor/contexts/ViewerContext", () => ({
  useViewer: () => ({
    activeFileId: mocks.active,
    setActiveFileId: mocks.setActive,
  }),
}));
vi.mock("@editor/contexts/NavigationContext", () => ({
  useNavigationActions: () => ({ actions: { setWorkbench: mocks.navigate } }),
  useNavigationGuard: () => ({
    requestNavigation: (action: () => void) => {
      mocks.guard(action);
      if (!mocks.blocked) action();
    },
  }),
}));
import { DocumentTabs } from "@editor/components/layout/DocumentTabs";
describe("document workspace tabs", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.active = "a";
    mocks.blocked = false;
  });
  it("focuses one document and selects it for processing", () => {
    render(<DocumentTabs />);
    fireEvent.click(screen.getByRole("tab", { name: "b.pdf" }));
    expect(mocks.setActive).toHaveBeenCalledWith("b");
    expect(mocks.selected).toHaveBeenCalledWith(["b"]);
    expect(mocks.navigate).toHaveBeenCalledWith("viewer");
  });
  it("closes an inactive tab without touching active document or library", () => {
    render(<DocumentTabs />);
    fireEvent.click(screen.getByRole("button", { name: "Close c.pdf" }));
    expect(mocks.remove).toHaveBeenCalledWith(["c"], false);
    expect(mocks.setActive).not.toHaveBeenCalled();
    expect(mocks.guard).not.toHaveBeenCalled();
  });
  it("selects the next neighbor when the active tab closes", () => {
    mocks.active = "b";
    render(<DocumentTabs />);
    fireEvent.click(screen.getByRole("button", { name: "Close b.pdf" }));
    expect(mocks.setActive).toHaveBeenCalledWith("c");
    expect(mocks.remove).toHaveBeenCalledWith(["b"], false);
  });
  it("selects the previous neighbor when the last tab closes", () => {
    mocks.active = "c";
    render(<DocumentTabs />);
    fireEvent.click(screen.getByRole("button", { name: "Close c.pdf" }));
    expect(mocks.setActive).toHaveBeenCalledWith("b");
  });
  it("retains unsaved active edits until the existing guard approves switching or closing", () => {
    mocks.blocked = true;
    render(<DocumentTabs />);
    fireEvent.click(screen.getByRole("tab", { name: "b.pdf" }));
    fireEvent.click(screen.getByRole("button", { name: "Close a.pdf" }));
    expect(mocks.guard).toHaveBeenCalledTimes(2);
    expect(mocks.setActive).not.toHaveBeenCalled();
    expect(mocks.remove).not.toHaveBeenCalled();
  });
  it("supports roving keyboard focus and selection", () => {
    render(<DocumentTabs />);
    expect(screen.getByRole("tab", { name: "a.pdf" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.keyDown(screen.getByRole("tab", { name: "a.pdf" }), {
      key: "End",
    });
    expect(mocks.setActive).toHaveBeenCalledWith("c");
    expect(screen.getByRole("tab", { name: "c.pdf" })).toHaveFocus();
  });
  it("switches with Ctrl+Tab and closes a tab with Ctrl+W without deleting storage", () => {
    render(<DocumentTabs />);
    fireEvent.keyDown(window, { key: "Tab", ctrlKey: true });
    expect(mocks.setActive).toHaveBeenCalledWith("b");
    fireEvent.keyDown(window, { key: "w", ctrlKey: true });
    expect(mocks.remove).toHaveBeenCalledWith(["a"], false);
  });
  it("cycles backwards with Ctrl+Shift+Tab", () => {
    render(<DocumentTabs />);
    fireEvent.keyDown(window, { key: "Tab", ctrlKey: true, shiftKey: true });
    expect(mocks.setActive).toHaveBeenCalledWith("c");
  });
});
