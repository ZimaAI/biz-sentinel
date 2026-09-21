"""Real HTTP/MySQL smoke checks. No model or browser mocks; credentials stay local."""
import http.cookiejar
import json
import os
import pathlib
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = os.environ.get("COMMERCE_VERIFY_URL", "http://127.0.0.1:8065/api/commerce/v1")
settings = dict(line.split("=", 1) for line in (ROOT / ".commerce-local/development.env").read_text(encoding="utf-8-sig").splitlines() if "=" in line)
checks = []


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ""

    def request(self, method, path, body=None, expected=200, headers=None):
        encoded = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(BASE + path, data=encoded, method=method, headers={"Content-Type": "application/json", "X-CSRF-Token": self.csrf, "Origin": "http://localhost:3000", **(headers or {})})
        try:
            response = self.opener.open(req, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read().decode()
        assert response.status == expected, (method, path, response.status, expected, raw[:500])
        if response.headers.get("Content-Type", "").startswith("application/json"):
            value = json.loads(raw)
            return value.get("data", value)
        return raw

    def login(self, username, password):
        self.csrf = self.request("GET", "/auth/session")["csrfToken"]
        self.csrf = self.request("POST", "/auth/session", {"username": username, "password": password})["csrfToken"]


def passed(name):
    checks.append(name)
    print("PASS", name, flush=True)


def wait_run(client, run_id, statuses):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        result = client.request("GET", "/runs/" + run_id)
        if result["status"] in statuses:
            return result
        if result["status"] in {"FAILED", "CANCELLED", "ACCESS_REVOKED"}:
            raise AssertionError(result)
        time.sleep(.25)
    raise AssertionError("Run timeout: " + json.dumps(result))


def cells(row):
    return {cell["field"]: cell["value"] for cell in row["cells"]}


admin = Client()
admin.request("GET", "/me", expected=401)
admin.login("admin", settings["COMMERCE_ADMIN_PASSWORD"])
me = admin.request("GET", "/me")
assert len(me["stores"]) == 3
assert len(admin.request("GET", "/metrics")) == 8
passed("session, CSRF, identity and eight metrics")
overview = admin.request("GET", "/overview?storeIds=s1,s2,s3&start=2026-09-20&endExclusive=2026-09-21&comparison=PREVIOUS_WEEK_SAME_DAYS")
totals = cells(overview["totals"])
for metric, expected in {"paid_gmv": "48620000", "paid_orders": "1842", "refund_amount": "2686000", "net_receipts": "45934000", "visitor_sessions": "46050"}.items():
    assert totals[metric] == expected, (metric, totals[metric])
assert len(overview["trend"]) == 7
passed("MySQL raw fixture golden totals and seven-day trend")
spec = {"metricIds": ["paid_gmv"], "dimensions": ["store"], "dateRange": {"start": "2026-09-20", "endExclusive": "2026-09-21"}, "comparison": "PREVIOUS_WEEK_SAME_DAYS", "filters": [], "limit": 100}
admin.request("POST", "/queries", {"requestedStoreIds": ["s1", "forbidden"], "spec": spec}, 403)
admin.request("POST", "/queries", {"requestedStoreIds": ["s1"], "spec": {**spec, "sql": "select 1"}}, 422)
admin.request("POST", "/queries", {"requestedStoreIds": ["s1"], "spec": {**spec, "dimensions": ["store", "sku"], "metricIds": ["refund_amount"]}}, 422)
passed("mixed unauthorized scope, arbitrary SQL and SKU refunds rejected")
request = {"question": "支付 GMV 为什么下降？", "requestedStoreIds": ["s1", "s2", "s3"], "dateRange": spec["dateRange"], "comparison": spec["comparison"], "requireApproval": True}
key = str(uuid.uuid4())
run = admin.request("POST", "/runs", request, 202, {"Idempotency-Key": key})
assert admin.request("POST", "/runs", request, 202, {"Idempotency-Key": key})["runId"] == run["runId"]
admin.request("POST", "/runs", {**request, "question": "不同问题"}, 409, {"Idempotency-Key": key})
run = wait_run(admin, run["runId"], {"WAITING_APPROVAL"})
approval = {"decision": "APPROVE", "planVersion": run["plan"]["planVersion"], "planHash": run["plan"]["planHash"], "expectedRunVersion": run["runVersion"]}
admin.request("POST", "/runs/" + run["runId"] + "/approval", {**approval, "planVersion": 0}, 409)
admin.request("POST", "/runs/" + run["runId"] + "/approval", approval)
admin.request("POST", "/runs/" + run["runId"] + "/approval", approval, 409)
completed = wait_run(admin, run["runId"], {"SUCCEEDED", "PARTIAL"})
assert completed["status"] == "SUCCEEDED", completed
report = admin.request("GET", "/reports/" + completed["reportId"])
assert any(claim["type"] == "DECOMPOSITION" for claim in report["claims"])
assert any("480" in claim["text"] for claim in report["claims"])
for ref in {ref for claim in report["claims"] for ref in claim["evidenceRefs"]}:
    assert admin.request("GET", "/evidence/" + ref)["datasetVersionId"] == report["datasetVersionId"]
assert "分析边界" in admin.request("GET", "/reports/" + completed["reportId"] + "/export")
replay = admin.request("GET", "/runs/" + run["runId"] + "/events?after=0")
assert "event:run.completed" in replay or "event: run.completed" in replay
assert '"sequence":1' in replay
passed("idempotency, stale/concurrent approvals, StateGraph, evidence, report export and SSE replay")
username = "scope-" + uuid.uuid4().hex[:8]
password = uuid.uuid4().hex
member = admin.request("POST", "/members", {"username": username, "password": password, "displayName": "隔离验证", "storeIds": ["s1"], "roles": ["STORE_OPERATOR"]})
operator = Client()
operator.login(username, password)
query = operator.request("POST", "/queries", {"requestedStoreIds": ["s1"], "spec": spec})
assert cells(query["totals"])["paid_gmv"] == "25500000"
operator.request("POST", "/queries", {"requestedStoreIds": ["s2"], "spec": spec}, 403)
operator.request("GET", "/reports/" + completed["reportId"], expected=404)
admin.request("PATCH", "/members/" + username, {"expectedVersion": 1, "storeIds": ["s2"], "roles": ["STORE_OPERATOR"], "enabled": True})
operator.request("GET", "/evidence/" + query["evidenceId"], expected=404)
admin.request("PATCH", "/members/" + username, {"expectedVersion": 2, "storeIds": [], "roles": ["VIEWER"], "enabled": False})
passed("real member grant isolation and historical evidence revocation")
existing_rules = admin.request("GET", "/anomaly-rules?limit=100")["items"]
rule = next((r for r in existing_rules if r["definition"]["name"] in {"支付 GMV 日监控", "HTTP 验证 GMV 日监控"}), None)
if rule is None:
    rule = admin.request("POST", "/anomaly-rules", {"name": "支付 GMV 日监控", "metricId": "paid_gmv", "storeIds": ["s1", "s2", "s3"], "direction": "DOWN", "baseline": "SAME_WEEKDAY_MEDIAN_8", "relativeThreshold": "0.15", "absoluteThreshold": "1000000", "unit": "CNY_CENT", "cooldownHours": 24, "enabled": True}, 201)
admin.request("PATCH", "/anomaly-rules/" + rule["ruleId"], {"expectedVersion": 0, "definition": rule["definition"]}, 409)
deadline = time.monotonic() + 80
while time.monotonic() < deadline:
    matching = [x for x in admin.request("GET", "/anomalies?limit=100")["items"] if x["ruleId"] == rule["ruleId"]]
    if matching:
        event = matching[0]
        assert event["current"]["value"] == "48620000"
        assert len(event["referenceDates"]) >= 4
        assert admin.request("POST", "/anomalies/" + event["anomalyId"] + "/acknowledge", {})["status"] == "ACKNOWLEDGED"
        break
    time.sleep(1)
else:
    raise AssertionError("Scheduled monitoring did not produce anomaly")
passed("scheduled complete-history alert and acknowledgment")
output = {"backend": BASE, "database": "MySQL 8.4", "fixture": overview["datasetVersionId"], "checks": checks, "runId": run["runId"], "reportId": completed["reportId"], "model": "DETERMINISTIC_LOCAL; no live model credentials used"}
(ROOT / "implementation/backend-http-validation.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"passed": len(checks), "reportId": completed["reportId"]}), flush=True)
