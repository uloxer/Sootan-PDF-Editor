import { useEffect, useRef } from "react";
import { useAllFiles, useFileActions } from "@editor/contexts/FileContext";
import { useViewer } from "@editor/contexts/ViewerContext";
import {
  useNavigationActions,
  useNavigationGuard,
} from "@editor/contexts/NavigationContext";
import { Icon } from "@editor/ui/Icon";
import type { FileId } from "@editor/types/file";
import "@editor/components/layout/DocumentTabs.css";

/** Workspace tabs close in-memory documents; library records and disk originals remain. */
export function DocumentTabs() {
  const { files } = useAllFiles();
  const { actions } = useFileActions();
  const { activeFileId, setActiveFileId } = useViewer();
  const { actions: navigation } = useNavigationActions();
  const { requestNavigation } = useNavigationGuard();
  const buttons = useRef(new Map<string, HTMLButtonElement>());
  const pdfs = files.filter((file) => file.name.toLowerCase().endsWith(".pdf"));
  const active = activeFileId ?? pdfs[0]?.fileId;
  const focus = (id: string) =>
    requestNavigation(() => {
      setActiveFileId(id);
      actions.setSelectedFiles([id as FileId]);
      navigation.setWorkbench("viewer");
      buttons.current.get(id)?.focus();
      buttons.current
        .get(id)
        ?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
    });
  const close = (id: FileId) => {
    const remove = () => {
      if (active === id) {
        const index = pdfs.findIndex((file) => file.fileId === id);
        const next = pdfs[index + 1] ?? pdfs[index - 1];
        setActiveFileId(next?.fileId ?? null);
        actions.setSelectedFiles(next ? [next.fileId] : []);
      }
      void actions.removeFiles([id], false);
    };
    if (active === id) requestNavigation(remove);
    else remove();
  };
  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey || !pdfs.length)
        return;
      if (event.key === "Tab") {
        event.preventDefault();
        const current = pdfs.findIndex((file) => file.fileId === active);
        const index =
          (current + (event.shiftKey ? pdfs.length - 1 : 1)) % pdfs.length;
        focus(pdfs[index].fileId);
      } else if (event.key.toLowerCase() === "w" && active) {
        event.preventDefault();
        close(active as FileId);
      }
    };
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  });
  if (!pdfs.length) return null;
  return (
    <div
      className="document-tabs"
      role="tablist"
      aria-label="Open PDF documents"
    >
      {pdfs.map((file, index) => (
        <div
          className="document-tab"
          key={file.fileId}
          data-active={active === file.fileId}
        >
          <button
            ref={(node) => {
              if (node) buttons.current.set(file.fileId, node);
              else buttons.current.delete(file.fileId);
            }}
            type="button"
            role="tab"
            aria-selected={active === file.fileId}
            tabIndex={active === file.fileId ? 0 : -1}
            title={file.name}
            onClick={() => focus(file.fileId)}
            onKeyDown={(event) => {
              let target: number | undefined;
              if (event.key === "ArrowRight")
                target = (index + 1) % pdfs.length;
              if (event.key === "ArrowLeft")
                target = (index + pdfs.length - 1) % pdfs.length;
              if (event.key === "Home") target = 0;
              if (event.key === "End") target = pdfs.length - 1;
              if (target !== undefined) {
                event.preventDefault();
                focus(pdfs[target].fileId);
              }
              if (event.key === "Delete") {
                event.preventDefault();
                close(file.fileId);
              }
            }}
          >
            <Icon name="file-text" size="1rem" />
            <span>{file.name}</span>
          </button>
          <button
            className="document-tab-close"
            type="button"
            aria-label={`Close ${file.name}`}
            title={`Close ${file.name}`}
            onClick={() => close(file.fileId)}
          >
            <Icon name="x" size="0.875rem" />
          </button>
        </div>
      ))}
    </div>
  );
}
