# Private non-web processing worker

Build: set JAVA_HOME to the workspace JDK25 and run `gradlew.bat :worker:installDist :worker:check :pdf-services:check`. Libraries land in `app/worker/build/install/worker/lib`. No web framework is included.

Golden check (using the existing private helper Python, which contains pikepdf/pypdfium2):

```powershell
& '.tooling/windows-package/Sootan PDF Editor/app/helpers/python/python.exe' packaging/worker/smoke.py --java .tooling/jdk-25.0.4.1+1/bin/java.exe --helpers '.tooling/windows-package/Sootan PDF Editor/app/helpers'
```

The native prototype takes private test-only environment overrides SOOTAN_WORKER_JAVA, SOOTAN_WORKER_LIB and SOOTAN_WORKER_HELPERS. The packaged defaults resolve only its private runtime/worker/helpers beside the app. Do not select an arbitrary Java command through the frontend.

Protocol1 request metadata contains requestId, an allowlisted operation, input (`input-main.pdf` in the host-created job), and explicit options. Each process owns one request. Result metadata names output.pdf, output.zip or output.json; progress and errors carry the same request ID. The worker never receives an arbitrary user-selected output path. The host streams input/output separately through exact virtual resources and owns lifetime/cancellation.

See [NO_SERVER_PHASE3.md](../../NO_SERVER_PHASE3.md) for tested scope and remaining parity, and migration-inventory.json for static retained-controller mappings. This is not the final standalone package or an upgrade instruction. The installed app and real profile remain untouched.
