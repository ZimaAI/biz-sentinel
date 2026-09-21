#!/usr/bin/env python3
"""Read-only deployment checks; credentials are never printed."""
import base64
import ipaddress
import json
import pathlib
import socket
import ssl
import subprocess
import urllib.error
import urllib.request

DOMAIN = "bizsentinel.zimagent.top"
STATE = pathlib.Path("/home/zima/.local/state/biz-sentinel-deploy")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = {}

listeners = subprocess.check_output(["ss", "-H", "-lnt"], text=True)
for port in [8065, 13000, 13307]:
    addresses = [line.split()[3] for line in listeners.splitlines() if line.split()[3].endswith(f":{port}")]
    loopback_only = bool(addresses)
    for address in addresses:
        try:
            ip = ipaddress.ip_address(address.rsplit(":", 1)[0].strip("[]"))
            if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
                ip = ip.ipv4_mapped
            loopback_only = loopback_only and ip.is_loopback
        except ValueError:
            loopback_only = False
    results[f"loopback_binding_{port}"] = {"addresses": addresses, "ok": loopback_only}


def check(name, url, expected, headers=None):
    try:
        request = urllib.request.Request(url, headers=headers or {})
        with opener.open(request, timeout=20) as response:
            status = response.status
            content = response.read()
        results[name] = {"status": status, "ok": status == expected, "bytes": len(content)}
    except urllib.error.HTTPError as exc:
        results[name] = {"status": exc.code, "ok": exc.code == expected}
    except Exception as exc:
        results[name] = {"ok": False, "error": str(exc)}


try:
    ips = sorted({x[4][0] for x in socket.getaddrinfo(DOMAIN, 443, family=socket.AF_INET)})
    results["dns"] = {"addresses": ips, "ok": ips == ["36.151.151.229"]}
except socket.gaierror as exc:
    results["dns"] = {"ok": False, "error": str(exc)}

check("frontend_loopback", "http://127.0.0.1:13000/agent/new", 200)
check("backend_loopback", "http://127.0.0.1:8065/api/agent/list", 200)
check("public_auth_required", f"https://{DOMAIN}/agent/new", 401)
check("public_api_auth_required", f"https://{DOMAIN}/api/agent/list", 401)
check("existing_site", "https://algomotion.zimagent.top/", 200)

login_file = STATE / "admin-login.txt"
if login_file.exists():
    login = dict(line.split("=", 1) for line in login_file.read_text().splitlines())
    auth = base64.b64encode(f"{login['USERNAME']}:{login['PASSWORD']}".encode()).decode()
    headers = {"Authorization": "Basic " + auth}
    check("authenticated_frontend", f"https://{DOMAIN}/agent/new", 200, headers)
    check("authenticated_api", f"https://{DOMAIN}/api/agent/list", 200, headers)
    check("model_configuration", f"https://{DOMAIN}/api/model-config/check-ready", 200, headers)

try:
    with socket.create_connection((DOMAIN, 443), timeout=10) as connection:
        with ssl.create_default_context().wrap_socket(connection, server_hostname=DOMAIN) as tls:
            certificate = tls.getpeercert()
            results["certificate"] = {
                "ok": True,
                "expires": certificate["notAfter"],
                "names": certificate.get("subjectAltName", []),
            }
except Exception as exc:
    results["certificate"] = {"ok": False, "error": str(exc)}

output = json.dumps(results, ensure_ascii=False, indent=2)
(STATE / "verification.json").write_text(output + "\n")
print(output)
raise SystemExit(0 if all(item["ok"] for item in results.values()) else 1)
