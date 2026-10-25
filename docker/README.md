# Local core container

Build with `docker build -t sootan-pdf-editor -f docker/embedded/Dockerfile .` or use the root `compose.yml`.

The pinned upstream PDF-tools base supplies OCR, Office conversion and imaging dependencies. The application builder includes only the MIT core; no portal, commercial SDKs or restricted source is copied. The service binds to all interfaces inside the container so port mapping works. Publish the host port on loopback (`127.0.0.1:8080:8080`) because this core has no authentication.

Docker was unavailable on the implementation host. The recipe and compose file are provided for reproducible verification; no successful image build or image-size/runtime measurement is claimed.
