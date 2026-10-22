"""
Runs the release's Portal upload against a local stand-in for the Portal and checks what it would
send: the feature compatibility the jar's descriptor declares must be in the request, since the
Portal refuses a version that drops a declaration an earlier version made.

The stand-in records each request and refuses it, so nothing leaves this machine and the build
fails after the first request; that failure is expected. Needs a release repository at the version,
for example `./gradlew :plugin:publishAllPublicationsToBuildLocalRepository -PreleaseVersion=<v>`.

    python3 scripts/check_portal_upload.py <version> [<release repository>]
"""

import http.server
import json
import os
import subprocess
import sys
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main(argv):
    version = argv[0]
    repository = argv[1] if len(argv) > 1 else os.path.join(ROOT, "plugin", "build", "local-repository")
    bodies = []

    class Portal(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            bodies.append(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"failed":true,"errorMessage":"stand-in Portal: recorded and refused"}')

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Portal)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    env = dict(os.environ, GRADLE_PORTAL_URL=f"http://127.0.0.1:{server.server_port}",
               GRADLE_PUBLISH_KEY="stand-in", GRADLE_PUBLISH_SECRET="stand-in")
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([gradlew, ":plugin:publishReleaseRepository", "-x", ":plugin:signReleaseRepository",
                    f"-PreleaseVersion={version}", "-Pyoriwake.sign", f"-Pyoriwake.repository={repository}",
                    "--console=plain"], cwd=ROOT, env=env, capture_output=True, text=True)
    server.shutdown()
    if not bodies:
        print("FAIL: the upload sent no request to the stand-in Portal")
        return 1
    features = json.loads(bodies[0]).get("featureCompatibility") or {}
    print(f"feature compatibility sent: {features}")
    if features.get("configuration-cache") != "DECLARED_SUPPORTED":
        print("FAIL: the request does not declare the configuration cache, which 0.2.0 declared")
        return 1
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
