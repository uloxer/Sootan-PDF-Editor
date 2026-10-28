# Firefox development package

Build the shared core UI with `task frontend:build:core`, then run `python3.11 packaging/firefox/build.py` from the repository root. The ignored output is `.tooling/firefox-package/sootan-pdf-editor-3.2.5-unsigned.xpi` and its unpacked `extension` directory. All assets remain bundled; no Java, local helper or HTTP listener is included.

This is an **unsigned development checkpoint**, not a tested release. It can be loaded temporarily through Firefox's supported development tools, and disappears at browser restart. Permanent distribution needs Mozilla signing. Do not disable signing or browser security settings.

See [NO_SERVER_PHASE2.md](../../NO_SERVER_PHASE2.md) for the accepted core tool inventory, preservation limits and the outstanding PDFium blob-worker/CSP adaptation. Actual Firefox rendering acceptance is blocked by the available computer tool's URL-policy restriction. Temporary installation alone does not establish working rendering or offline behavior.
