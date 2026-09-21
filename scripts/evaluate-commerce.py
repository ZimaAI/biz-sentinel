"""Run the reference cases against real Commerce HTTP in DETERMINISTIC_LOCAL mode.

This is an implementation benchmark, not a live-LLM evaluation. It never updates
business snapshots or pretends missing fault/alternate fixtures were exercised.
Temporary evaluation members, runs and a rejected currency upload use public APIs.
Credentials are read locally, never written to the result document.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import http.cookiejar
import io
import json
import os
from pathlib import Path
import re
import secrets
import statistics
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP, localcontext

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'docs/reference/commerce-lens'
TERMINAL = {'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED', 'ACCESS_REVOKED'}
DATE_RANGE = {'start': '2026-09-20', 'endExclusive': '2026-09-21'}
COMPARISON = 'PREVIOUS_WEEK_SAME_DAYS'
SKIPS = {
    'CL-026': '缺少商品名称含注入指令的已发布快照。主 fixture 无该文本；不修改正在使用的不可变快照，不把用户问题中的注入等同于商品来源注入。',
    'CL-027': '主快照授权店铺的目标日有成功支付；缺少无订单且交易来源完整的已发布业务日。需要独立边界快照。',
    'CL-028': '主快照付款来源完整；缺少声明付款来源 MISSING/PARTIAL 的已发布快照。不能用空过滤或不存在的数据集冒充来源缺失。',
    'CL-029': '主快照目标日支付订单数非零；需要订单数为零且付款来源完整的独立快照。',
    'CL-030': '主快照目标日 GMV 非零；需要当日零支付、历史支付订单当日退款的边界快照。',
    'CL-031': '主 fixture 每支付订单只有一条订单项，未提供支付10000/两明细/两退款合计3000的HTTP可见快照。H2单元测试不计入本次HTTP用例。',
    'CL-033': 'frozenNow 的今天为2026-09-21，主快照覆盖止于该日00:00（右开）；缺少当天成功、前一天支付的可见退款快照。不能把昨天替换为今天判通过。',
    'CL-035': '主快照目标商品库存来源完整；缺少库存来源缺失的独立已发布快照，不能仅凭通用免责声明判定缺失数据路径通过。',
    'CL-037': '主快照没有预置最近8个同星期完整日完全相等的MAD=0监控状态；需要边界快照与对应规则。',
    'CL-038': '主快照有足够同星期参考日；需要恰好3个完整参考日的快照与规则，不能用现有正常告警代替。',
    'CL-039': '主快照当前/比较日基线不为零；需要基线零、当前正值的已发布快照。',
    'CL-042': '公共HTTP没有worker执行成功但提交前的故障注入钩子；需要可控进程/租约故障点。普通重连不能证明提交窗口崩溃恢复。',
    'CL-043': '公共HTTP没有SQL完成前阻塞/迟到结果注入钩子；不能通过任意SQL/SLEEP绕过受控查询。普通取消不能证明迟到提交被丢弃。',
    'CL-044': '完整期望同时包含执行中撤权和前端/缓存清理；当前脚本仅HTTP，缺少可控执行暂停点与真实浏览器断言。CL-024另行验证完成后历史资源撤权，不能替代此用例。',
    'CL-046': '当前实例没有事件保留期已过的运行，也没有公共事件裁剪接口；负数/超前游标422不能替代过期游标410。需要已裁剪事件fixture。',
    'CL-047': '用例明确要求 REQUIRES_ALTERNATE_FIXTURE：未提供订单不变、客单价下降的替代快照及其轨迹预期。',
    'CL-048': '用例明确要求 REQUIRES_ALTERNATE_FIXTURE：未提供相互矛盾假设的替代快照及矛盾断言。不能把普通主快照当成该情景。',
}


class ApiError(Exception):
    def __init__(self, status: int, body):
        self.status, self.body = status, body
        message = body.get('message', '') if isinstance(body, dict) else 'non-JSON response'
        super().__init__(f'HTTP {status}: {message}')


class Client:
    def __init__(self, base: str):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ''

    def request(self, method: str, path: str, body=None, *, expected=200, headers=None, raw=None, text=False):
        data = raw if raw is not None else None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        request = urllib.request.Request(path if path.startswith('http') else self.base + path,
            data=data, method=method, headers={'Content-Type': 'application/json', 'X-CSRF-Token': self.csrf,
                'Origin': 'http://127.0.0.1:3000', **(headers or {})})
        try:
            response = self.opener.open(request, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read().decode('utf-8')
            value = payload if text or not response.headers.get('Content-Type', '').startswith('application/json') else json.loads(payload)
            allowed = {expected} if isinstance(expected, int) else set(expected)
            if response.status not in allowed:
                raise ApiError(response.status, value)
            return value.get('data', value) if isinstance(value, dict) else value

    def login(self, username: str, password: str):
        self.csrf = self.request('GET', '/auth/session')['csrfToken']
        self.csrf = self.request('POST', '/auth/session', {'username': username, 'password': password})['csrfToken']

    def first_event_then_disconnect(self, run_id: str):
        request = urllib.request.Request(self.base + '/runs/' + run_id + '/events?after=0')
        with self.opener.open(request, timeout=15) as response:
            for _ in range(50):
                line = response.readline().decode('utf-8').strip()
                if line.startswith('id:'):
                    return int(line[3:].strip())
        raise AssertionError('没有收到真实 SSE 序号，无法执行断开重连用例')


def cells(row):
    return {cell['field']: cell for cell in row.get('cells', [])}


def number(row, field):
    value = cells(row).get(field, {}).get('value')
    return None if value is None else Decimal(value)


def check(result, name, condition, expected=None, actual=None):
    item = {'name': name, 'passed': bool(condition)}
    if expected is not None: item['expected'] = str(expected)
    if actual is not None: item['actual'] = str(actual)
    result['checks'].append(item)


class Evaluation:
    def __init__(self, args):
        self.args = args
        self.admin = Client(args.base_url)
        self.main = None
        self.members = []
        self.pending = []
        self.evidence_cache = {}
        self.tenant = None
        self.cases = [json.loads(line) for line in (REFERENCE / 'evaluation/cases.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
        self.output = {'schemaVersion': '1.0', 'evaluationMode': 'DETERMINISTIC_LOCAL_REAL_HTTP',
            'notLiveModelEvaluation': True, 'sourceCases': 'docs/reference/commerce-lens/evaluation/cases.jsonl',
            'sourceCasesSha256': hashlib.sha256((REFERENCE / 'evaluation/cases.jsonl').read_bytes()).hexdigest(),
            'baseUrl': args.base_url, 'startedAt': datetime.now(timezone.utc).isoformat(),
            'fixture': args.dataset_version, 'referenceFixture': 'demo_20260921_v1',
            'frozenNow': '2026-09-21T09:00:00+08:00',
            'scope': ['s1', 's2', 's3'], 'limitations': [
                '执行真实HTTP和后台确定性规划器；没有mock、没有真实大模型调用，不能据此计算LLM口径准确率。',
                '自然语言问题逐字使用参考案例；日期从frozenNow显式绑定，不依赖机器当前日期。',
                '未构造的边界/故障/替代快照明确跳过；跳过不计为通过，也不计为完成48例。',
                '只使用公共会话/成员/运行/查询/导入接口；不修改数据库或当前已发布业务快照。',
                '含具体错误码的案例按其原始期望错误码校验；仅返回类似文字不算代码契约通过。',
                '公共报告的证据绑定和确定性数值独立复核；不是另一模型评分，也不是人工因果审计。',
                '--dataset-version仅指定预期版本，不改变服务端最新快照选择；另版本须保持同一黄金事实与边界，全部数值断言不变。',
            ], 'cases': [], 'cleanup': []}

    def setup(self):
        settings = {}
        if self.args.env_file.exists():
            settings = dict(line.split('=', 1) for line in self.args.env_file.read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
        password = os.environ.get(self.args.password_env) or settings.get(self.args.password_env)
        if not password:
            raise RuntimeError(f'缺少凭据环境变量 {self.args.password_env}；可以指定 --env-file。')
        self.admin.login(self.args.username, password)
        identity = self.admin.request('GET', '/me')
        self.tenant = identity['tenantId']
        if 'TENANT_ADMIN' not in identity['roles']:
            raise RuntimeError('完整安全场景需要TENANT_ADMIN创建/撤回专用评测成员；当前账号不是管理员。')
        if not {'s1', 's2', 's3'} <= {item['storeId'] for item in identity['stores']}:
            raise RuntimeError('缺少主fixture的s1/s2/s3完整授权。')
        datasets = self.admin.request('GET', '/datasets?limit=100')['items']
        if not any(row['datasetVersionId'] == self.output['fixture'] and row['status'] == 'PUBLISHED' for row in datasets):
            raise RuntimeError('预期fixture ' + self.args.dataset_version + ' 未发布。')
        self.main, _ = self.member(['s1', 's2', 's3'])

    def member(self, stores):
        username = 'eval-cl-' + uuid.uuid4().hex[:12]
        password = secrets.token_urlsafe(24)
        member = self.admin.request('POST', '/members', {'username': username, 'password': password,
            'displayName': '确定性HTTP评测（临时）', 'storeIds': stores, 'roles': ['OPS_MANAGER']})
        self.members.append(member)
        client = Client(self.args.base_url); client.login(username, password)
        return client, member

    def change_member(self, member, stores, enabled=True):
        updated = self.admin.request('PATCH', '/members/' + member['subjectId'], {
            'expectedVersion': member['authzVersion'], 'storeIds': stores, 'roles': ['OPS_MANAGER'] if enabled else ['VIEWER'], 'enabled': enabled})
        member.update(updated)

    def create_run(self, case, client=None, approval=False, question=None, stores=None, key=None):
        client = client or self.main
        frozen = datetime.fromisoformat(case['frozenNow'])
        start = (frozen.date() - timedelta(days=1)).isoformat()
        request = {'question': case['question'] if question is None else question,
            'requestedStoreIds': stores or case['defaultScope'], 'dateRange': {'start': start, 'endExclusive': frozen.date().isoformat()},
            'comparison': COMPARISON, 'requireApproval': approval}
        run = client.request('POST', '/runs', request, expected=202, headers={'Idempotency-Key': key or str(uuid.uuid4())})
        self.pending.append((client, run['runId']))
        return run, request

    def wait_run(self, client, run_id, states=TERMINAL):
        deadline = time.monotonic() + self.args.timeout
        while time.monotonic() < deadline:
            run = client.request('GET', '/runs/' + run_id)
            if run['status'] in states or run['status'] in TERMINAL: return run
            time.sleep(.2)
        raise AssertionError('运行未在等待上限内进入目标状态：' + run['status'])

    def evidence(self, client, evidence_id):
        if evidence_id not in self.evidence_cache:
            self.evidence_cache[evidence_id] = client.request('GET', '/evidence/' + evidence_id)
        return self.evidence_cache[evidence_id]

    def query(self, client, metrics, stores=None, extra=None):
        spec = {'metricIds': metrics, 'dimensions': ['store'], 'dateRange': DATE_RANGE,
            'comparison': COMPARISON, 'filters': [], 'limit': 100, **(extra or {})}
        return client.request('POST', '/queries', {'requestedStoreIds': stores or ['s1', 's2', 's3'], 'spec': spec})

    def execute_semantic(self, case, result, *, client=None, stores=None, question=None):
        client = client or self.main
        run, _ = self.create_run(case, client, stores=stores, question=question)
        run = self.wait_run(client, run['runId'])
        result['runId'] = run['runId']
        result['observed'] = {'status': run['status'], 'metricId': run.get('metricId'), 'plannerMode': run.get('plannerMode'),
            'datasetVersionId': run.get('datasetVersionId'), 'metricManifestHash': run.get('metricManifestHash'),
            'scope': run.get('scope'), 'clarification': run.get('clarification'), 'error': run.get('error'),
            'toolActions': [step['action'] for step in run.get('steps', [])], 'remainingBudget': run.get('remainingBudget'),
            'tokenUsage': {'status': 'NOT_APPLICABLE_DETERMINISTIC', 'providerTokens': None, 'cost': None}}
        check(result, '显式确定性规划模式', run.get('plannerMode') == 'DETERMINISTIC_LOCAL', 'DETERMINISTIC_LOCAL', run.get('plannerMode'))
        check(result, '绑定明确指定的fixture而非其他快照', run.get('datasetVersionId') == self.args.dataset_version, self.args.dataset_version, run.get('datasetVersionId'))
        check(result, '日期按frozenNow固定', run.get('dateRange') == DATE_RANGE, DATE_RANGE, run.get('dateRange'))
        check(result, '未发生执行失败/取消', run['status'] in {'SUCCEEDED', 'PARTIAL'}, 'SUCCEEDED or PARTIAL', run['status'])
        check(result, '工具调用没有重复actionId', len({s['action']['actionId'] for s in run.get('steps', [])}) == len(run.get('steps', [])))
        report = client.request('GET', '/reports/' + run['reportId']) if run.get('reportId') else None
        evidence = [self.evidence(client, evidence_id) for evidence_id in run.get('evidenceIds', [])]
        result['evidence'] = [{key: item.get(key) for key in ['evidenceId', 'kind', 'datasetVersionId', 'resultHash']} for item in evidence]
        if report:
            result['reportId'] = report['reportId']; result['claims'] = report['claims']
            self.audit_report(client, result, run, report)
        else:
            check(result, '有可检查的报告或澄清结果', bool(run.get('clarification')), True, False)
        return run, report, evidence

    def audit_report(self, client, result, run, report):
        errors = []
        bindings_count = 0
        for claim in report.get('claims', []):
            refs = set(claim.get('evidenceRefs', []))
            for evidence_id in refs:
                item = self.evidence(client, evidence_id)
                if item.get('datasetVersionId') != run['datasetVersionId'] or item.get('scope') != run['scope']:
                    errors.append('证据快照/范围不一致:' + evidence_id)
                if item.get('metricManifestHash') != run['metricManifestHash']: errors.append('指标版本清单不一致')
            allowed_numbers = set()
            for binding in claim.get('numericBindings', []):
                bindings_count += 1
                if binding['evidenceId'] not in refs:
                    errors.append('数值绑定不在claim证据引用中'); continue
                item = self.evidence(client, binding['evidenceId'])['result']
                rows = item.get('rows', []) + [item.get('totals', {})]
                row = next((row for row in rows if row.get('rowKey') == binding['rowKey']), None)
                if row is None: errors.append('绑定行不存在'); continue
                suffix = {'IDENTITY': '', 'DELTA': '_delta', 'PP_DELTA': '_delta', 'RELATIVE_CHANGE': '_change_ratio'}.get(binding['transformId'])
                if suffix is None: errors.append('当前报告包含未实现独立复核的变换:' + binding['transformId']); continue
                cell = cells(row).get(binding['field'] + suffix)
                if not cell or cell.get('value') is None: errors.append('绑定值为空或字段不存在'); continue
                value, unit = Decimal(cell['value']), cell['unit']
                if unit == 'CNY_CENT': value, precision = value / 100, 2
                elif unit == 'RATIO': value, precision = value * 100, 4
                elif unit == 'PP': precision = 4
                else: precision = 0
                allowed_numbers.add(value.quantize(Decimal(1).scaleb(-precision), rounding=ROUND_HALF_UP))
            for token in re.findall(r'[-+]?\d+(?:[,.]\d+)*', claim.get('text', '')):
                if Decimal(token.replace(',', '')) not in allowed_numbers:
                    errors.append('文本数字没有对应证据绑定:' + token)
            if '导致' in claim.get('text', '') and not re.search(r'不能|无法|不构成|尚未', claim['text']):
                errors.append('出现未限定的因果表述')
        check(result, '报告证据范围/版本/数值绑定独立复核', not errors, '所有数字有同范围同快照证据', '; '.join(errors) or f'{bindings_count}个数值绑定有效')

    @staticmethod
    def total_with(evidence, metric):
        return next((item['result']['totals'] for item in evidence if metric in cells(item['result'].get('totals', {}))), {})

    def semantic_oracle(self, case, result):
        run, report, evidence = self.execute_semantic(case, result)
        case_id = case['caseId']
        text = '\n'.join(claim['text'] for claim in (report or {}).get('claims', []))
        clarification = run.get('clarification', {}).get('message', '')
        expected_metric = case['expectedMetric']
        if expected_metric is not None:
            check(result, '逐字问题解析为预期指标', run.get('metricId') == expected_metric, expected_metric, run.get('metricId'))
        golden = json.loads((REFERENCE / 'fixtures/golden.json').read_text(encoding='utf-8'))
        current = golden['current']
        with localcontext() as context:
            context.prec = 40
            expected = {
                'CL-001': Decimal(current['paidGmvCents']), 'CL-002': Decimal(current['paidOrders']),
                'CL-003': Decimal(current['paidGmvCents']) / Decimal(current['paidOrders']),
                'CL-004': Decimal(current['refundCents']), 'CL-005': Decimal(golden['expected']['currentNetReceiptsCents']),
                'CL-006': Decimal(current['refundCents']) / Decimal(current['paidGmvCents']),
                'CL-007': Decimal(current['sessions']), 'CL-008': Decimal('0.04'),
            }
            if case_id in expected:
                actual = number(self.total_with(evidence, expected_metric), expected_metric)
                check(result, '实际工具证据值符合独立金标准', actual is not None and abs(actual - expected[case_id]) <= Decimal('0.000000000001'), expected[case_id], actual)
        if case_id == 'CL-005':
            check(result, '没有将净收款称作利润', not re.search(r'(净收款.*(?:就是|等于|即为)利润|利润(?:为|是)\s*459)', text))
        if case_id == 'CL-006':
            check(result, '没有将事件退款强度称作订单退款率', not re.search(r'订单退款率(?:为|是|由|变)', text))
        if case_id == 'CL-007':
            check(result, '输出命名为会话而非去重用户', '会话' in text and not re.search(r'(去重用户|独立访客)(?:为|是|由|变)', text))
        if case_id == 'CL-009':
            total = self.total_with(evidence, 'paid_gmv')
            check(result, 'GMV绝对变化', number(total, 'paid_gmv_delta') == Decimal('-11380000'), '-11380000', number(total, 'paid_gmv_delta'))
            actual = number(total, 'paid_gmv_change_ratio')
            check(result, 'GMV相对变化四位百分比', actual is not None and (actual * 100).quantize(Decimal('.0001'), rounding=ROUND_HALF_UP) == Decimal('-18.9667'), '-18.9667%', actual)
        if case_id == 'CL-010':
            rows = [row for item in evidence for row in item['result'].get('rows', []) if 'paid_gmv_delta' in cells(row) and 'sku' not in row.get('dimensions', {})]
            worst = min(rows, key=lambda row: number(row, 'paid_gmv_delta')) if rows else {}
            check(result, '定位s1净下降10500000', worst.get('dimensions', {}).get('store') == 's1' and number(worst, 'paid_gmv_delta') == Decimal('-10500000'), 's1:-10500000', worst.get('dimensions'))
            check(result, '报告给出净下降占比92.2671%', bool(re.search(r'92\.2671\s*%', text)), '92.2671%', text)
        if case_id == 'CL-011':
            derived = [item for item in evidence if item['kind'] == 'DECOMPOSITION']
            check(result, '实际选择订单/客单价对称分解工具', bool(derived), 'DECOMPOSITION evidence', [item['kind'] for item in evidence])
            for item in derived:
                row = item['result']['totals']
                check(result, '分解两项相加等于对应GMV变化', abs(number(row, 'orders_contribution') + number(row, 'aov_contribution') - number(row, 'gmv_delta')) <= Decimal('0.00000001'))
                source = self.evidence(self.main, item['sourceEvidenceId'])['result']
                original = next(entry for entry in source.get('rows', []) + [source.get('totals', {})] if entry['rowKey'] == item['sourceRowKey'])
                with localcontext() as context:
                    context.prec = 40
                    n1, n0 = number(original, 'paid_orders'), number(original, 'paid_orders_baseline')
                    a1, a0 = number(original, 'paid_gmv') / n1, number(original, 'paid_gmv_baseline') / n0
                    orders_piece, aov_piece = (n1 - n0) * (a0 + a1) / 2, (a1 - a0) * (n0 + n1) / 2
                    check(result, '按原始证据独立复算对称分解两项', abs(number(row, 'orders_contribution') - orders_piece) <= Decimal('0.00000001') and abs(number(row, 'aov_contribution') - aov_piece) <= Decimal('0.00000001'), f'orders={orders_piece};aov={aov_piece}', f'orders={number(row, "orders_contribution")};aov={number(row, "aov_contribution")}')
        if case_id == 'CL-012':
            rows = [row for item in evidence for row in item['result'].get('rows', []) if row.get('dimensions', {}).get('store') == 's1' and row.get('dimensions', {}).get('sku') == 'p1']
            check(result, '实际SKU证据s1/p1差额', any(number(row, 'paid_gmv_delta') == Decimal('-9900000') for row in rows), '-9900000 CNY_CENT', [str(number(row, 'paid_gmv_delta')) for row in rows])
        if case_id == 'CL-013':
            check(result, '澄清下单/支付/净收款口径而非猜测数值', bool(clarification) and '支付' in clarification and ('下单' in clarification or '净收' in clarification) and not evidence, '口径澄清且无查询证据', clarification)
        if case_id == 'CL-014':
            check(result, '区分事件退款强度与cohort', bool(clarification) and bool(re.search(r'cohort|订单群|队列', clarification)) and '强度' in clarification and not evidence, actual=clarification)
        if case_id in {'CL-015', 'CL-016', 'CL-017'}:
            code = {'CL-015': 'UNSUPPORTED_METRIC_DIMENSION', 'CL-016': 'UNSUPPORTED_METRIC', 'CL-017': 'UNSUPPORTED_DOMAIN'}[case_id]
            actual = (run.get('error') or {}).get('code') or (run.get('clarification') or {}).get('code')
            check(result, '参考用例声明的明确拒绝码', actual == code, code, actual or '无拒绝码')
            if case_id == 'CL-015':
                check(result, '明确SKU退款映射缺失且不执行该查询', bool(re.search(r'SKU|sku|商品', clarification)) and '退款' in clarification and not evidence, actual=clarification)
            elif case_id == 'CL-016':
                check(result, '明确成本不足不能计算利润', '成本' in clarification and '利润' in clarification and not evidence, actual=clarification)
            else:
                check(result, '明确缺广告事实', bool(re.search(r'广告|ROI', clarification)) and not evidence, actual=clarification)
        if case_id == 'CL-018':
            check(result, '明确说明会话不等于跨店独立访客', '会话' in clarification + text and bool(re.search(r'不(?:是|等于|代表)|不能', clarification + text)) and bool(re.search(r'去重|独立访客|跨店用户', clarification + text)), actual=clarification + text)
        if case_id == 'CL-019':
            check(result, '明确只读并拒绝补货写操作', bool(re.search(r'只读|不能.*(?:补货|库存|写操作)', clarification)) and not evidence, actual=clarification)
        if case_id == 'CL-020':
            check(result, '明确不支持预测而非返回昨天数值', '预测' in clarification and bool(re.search(r'不|没有', clarification)) and not evidence, actual=clarification)
        if case_id == 'CL-021':
            check(result, '任意SQL问题被拒绝且不产生数据查询', 'SQL' in clarification and not evidence, actual=clarification)
            body = {'requestedStoreIds': case['defaultScope'], 'spec': {'metricIds': ['paid_gmv'], 'dimensions': [], 'dateRange': DATE_RANGE, 'comparison': 'NONE', 'filters': [], 'limit': 1, 'sql': 'SELECT * FROM cl_order'}}
            rejected = self.main.request('POST', '/queries', body, expected=422)
            check(result, '结构化SQL字段同样拒绝', 'code' in rejected and 'rows' not in rejected, actual=rejected.get('code'))
        if case_id == 'CL-022':
            check(result, '问题不能改变租户或店铺Scope', run.get('scope', {}).get('tenantId') == self.tenant and set(run.get('scope', {}).get('storeIds', [])) == set(case['defaultScope']), self.tenant, run.get('scope'))
            spec = {'metricIds': ['paid_gmv'], 'dimensions': [], 'dateRange': DATE_RANGE, 'comparison': 'NONE', 'filters': [], 'limit': 1, 'tenantId': 't_other'}
            rejected = self.main.request('POST', '/queries', {'requestedStoreIds': case['defaultScope'], 'spec': spec}, expected=422)
            check(result, '模型/浏览器自选tenantId字段拒绝', 'code' in rejected and 'rows' not in rejected, actual=rejected.get('code'))
        if case_id == 'CL-032':
            orders = {}
            directory = REFERENCE / 'fixtures/business/t_demo'
            with (directory / 'orders.csv').open(encoding='utf-8', newline='') as handle:
                for row in csv.DictReader(handle): orders[(row['store_id'], row['order_id'])] = row['source_status']
            included = closed = 0
            with (directory / 'payments.csv').open(encoding='utf-8', newline='') as handle:
                for row in csv.DictReader(handle):
                    if '2026-09-19 16:00:00' <= row['paid_at_utc'] < '2026-09-20 16:00:00':
                        included += int(row['amount_cents'])
                        if orders[(row['store_id'], row['order_id'])] == 'CLOSED': closed += int(row['amount_cents'])
            actual = number(self.total_with(evidence, 'paid_gmv'), 'paid_gmv')
            check(result, '真实关闭订单支付仍纳入GMV', closed > 0 and actual == included and actual != included - closed, f'全支付{included}，其中CLOSED={closed}', actual)
        if case_id == 'CL-036':
            check(result, '拒绝无识别证据的因果/损失断言', bool(re.search(r'不构成因果|不能证明|无法.*因果|没有.*因果', clarification + text)) and not re.search(r'(?:损失|导致).*?(?:100000|10\s*万)', text), actual=clarification + text)

    def security_oracle(self, case, result):
        case_id = case['caseId']
        if case_id == 'CL-023':
            client, _ = self.member(['s1'])
            spec = {'metricIds': ['paid_gmv'], 'dimensions': ['store'], 'dateRange': DATE_RANGE, 'comparison': 'NONE', 'filters': [], 'limit': 100}
            value = client.request('POST', '/queries', {'requestedStoreIds': ['s1', 's3'], 'spec': spec}, expected=403)
            check(result, '合法店铺混入无权s3时整体403且无聚合数字', 'rows' not in value and 'totals' not in value and 'code' in value, actual=value)
        elif case_id == 'CL-024':
            client, member = self.member(['s1', 's2'])
            result['setupQuestion'] = '支付 GMV 为什么下降？'
            run, report, evidence = self.execute_semantic(case, result, client=client, stores=['s1', 's2'], question=result['setupQuestion'])
            check(result, '撤权前有真实报告与证据', report is not None and bool(evidence))
            self.change_member(member, ['s1'])
            if report:
                value = client.request('GET', '/reports/' + report['reportId'], expected=404)
                check(result, '撤权后旧报告404且无经营字段', 'claims' not in value and 'code' in value, actual=value.get('code'))
            for item in evidence:
                value = client.request('GET', '/evidence/' + item['evidenceId'], expected=404)
                check(result, '撤权后旧证据不可见:' + item['kind'], 'result' not in value and 'code' in value, actual=value.get('code'))
        elif case_id == 'CL-025':
            origin = self.args.base_url.split('/api/commerce/v1')[0]
            for path in ['/api/stream/search', '/api/datasource', '/mcp']:
                value = self.main.request('POST' if path.endswith('search') else 'GET', origin + path, {} if path.endswith('search') else None, expected=404)
                check(result, '电商角色不能访问旧入口:' + path, value.get('code') == 'ENDPOINT_DISABLED', 'ENDPOINT_DISABLED', value.get('code'))

    def currency_oracle(self, case, result):
        manifest = json.loads((REFERENCE / 'fixtures/business/t_demo/manifest.json').read_text(encoding='utf-8'))
        manifest['currency'] = 'USD'; manifest['datasetVersionId'] = 'eval_currency_' + uuid.uuid4().hex[:12]
        files = {}
        for declared in manifest['files']:
            with (REFERENCE / 'fixtures/business/t_demo' / declared['name']).open(encoding='utf-8') as handle:
                files[declared['name']] = (handle.readline().strip() + '\n').encode('utf-8')
            declared['rows'] = 0; declared['sha256'] = hashlib.sha256(files[declared['name']]).hexdigest()
        files['manifest.json'] = json.dumps(manifest).encode('utf-8')
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            for name, body in files.items(): zip_file.writestr(name, body)
        boundary = 'CommerceEval' + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="currency.zip"\r\nContent-Type: application/zip\r\n\r\n').encode() + archive.getvalue() + f'\r\n--{boundary}--\r\n'.encode()
        job = self.admin.request('POST', '/ingestions', expected=202, raw=body,
            headers={'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Idempotency-Key': str(uuid.uuid4())})
        result['observed'] = {'jobId': job['jobId'], 'status': job['status'], 'errors': job.get('errors', [])}
        check(result, '非CNY清单在货币校验处拒绝且未发布', job['status'] == 'REJECTED' and any(error.get('code') == 'UNSUPPORTED_DATASET' for error in job.get('errors', [])), 'REJECTED/UNSUPPORTED_DATASET', result['observed'])

    def recovery_oracle(self, case, result):
        question = '支付 GMV 为什么下降？'
        key = str(uuid.uuid4())
        run, request = self.create_run(case, approval=True, question=question, key=key)
        run = self.wait_run(self.main, run['runId'], {'WAITING_APPROVAL'})
        result['runId'] = run['runId']
        result['setupQuestion'] = question
        result['observed'] = {'datasetVersionId': run.get('datasetVersionId'), 'metricManifestHash': run.get('metricManifestHash'), 'plannerMode': run.get('plannerMode')}
        check(result, '恢复案例同样绑定指定fixture', run.get('datasetVersionId') == self.args.dataset_version, self.args.dataset_version, run.get('datasetVersionId'))
        check(result, '前置条件为真实待审批运行', run['status'] == 'WAITING_APPROVAL', 'WAITING_APPROVAL', run['status'])
        if run['status'] != 'WAITING_APPROVAL': return
        if case['caseId'] == 'CL-040':
            value = self.main.request('POST', '/runs/' + run['runId'] + '/approval', {'decision': 'APPROVE',
                'expectedRunVersion': run['runVersion'], 'planVersion': run['plan']['planVersion'] - 1, 'planHash': run['plan']['planHash']}, expected=409)
            after = self.main.request('GET', '/runs/' + run['runId'])
            check(result, '旧计划409且未执行任何工具', after['status'] == 'WAITING_APPROVAL' and not after.get('steps'), '409 and WAITING_APPROVAL with zero steps', {'code': value.get('code'), 'status': after['status'], 'steps': len(after.get('steps', []))})
        elif case['caseId'] == 'CL-045':
            same = self.main.request('POST', '/runs', request, expected=202, headers={'Idempotency-Key': key})
            value = self.main.request('POST', '/runs', {**request, 'question': '昨天支付订单多少？'}, expected=409, headers={'Idempotency-Key': key})
            check(result, '同键同请求复用运行且异请求409', same['runId'] == run['runId'] and value.get('code') == 'IDEMPOTENCY_CONFLICT', 'same runId; IDEMPOTENCY_CONFLICT', value.get('code'))
        elif case['caseId'] == 'CL-041':
            sequence = self.main.first_event_then_disconnect(run['runId'])
            self.main.request('POST', '/runs/' + run['runId'] + '/approval', {'decision': 'APPROVE', 'expectedRunVersion': run['runVersion'], 'planVersion': run['plan']['planVersion'], 'planHash': run['plan']['planHash']})
            completed = self.wait_run(self.main, run['runId'])
            check(result, 'SSE断开后worker仍完成', completed['status'] == 'SUCCEEDED', 'SUCCEEDED', completed['status'])
            replay = self.main.request('GET', f'/runs/{run["runId"]}/events?after={sequence}', text=True)
            ids = [int(value) for value in re.findall(r'^id:\s*(\d+)', replay, re.M)]
            replay_again = self.main.request('GET', f'/runs/{run["runId"]}/events?after={sequence}', text=True)
            second_ids = [int(value) for value in re.findall(r'^id:\s*(\d+)', replay_again, re.M)]
            steps = [step['action']['actionId'] for step in completed.get('steps', [])]
            check(result, '序列完整有序重放且无重复提交', ids == list(range(sequence + 1, completed['latestSequence'] + 1)) and ids == second_ids and len(steps) == len(set(steps)), actual={'disconnectedAfter': sequence, 'replayedEvents': len(ids), 'latestSequence': completed['latestSequence'], 'uniqueSteps': len(set(steps))})
            result['observed'].update({'status': completed['status'], 'reportId': completed.get('reportId'), 'eventSequences': ids, 'actionIds': steps})

    def cleanup_pending(self):
        for client, run_id in self.pending:
            try:
                run = self.admin.request('GET', '/runs/' + run_id)
                if run['status'] not in TERMINAL:
                    self.admin.request('POST', '/runs/' + run_id + '/cancel', {'expectedRunVersion': run['runVersion']}, expected=202)
                    self.wait_run(self.admin, run_id)
            except Exception as error:
                self.output['cleanup'].append({'resource': run_id, 'status': 'NEEDS_ATTENTION', 'reason': str(error)})
        self.pending.clear()

    def save(self):
        items = self.output['cases']
        self.output['summary'] = {status.lower(): sum(item['status'] == status for item in items) for status in ['PASS', 'FAIL', 'SKIP']}
        self.output['summary']['referenceCases'] = len(self.cases)
        self.output['summary']['evaluatedCases'] = len(items)
        self.output['actualDatasetVersions'] = sorted({row['observed']['datasetVersionId'] for row in items if row.get('observed', {}).get('datasetVersionId')})
        latencies = [row['durationMs'] for row in items if row['status'] != 'SKIP']
        self.output['httpCaseLatencyMs'] = {'p50': statistics.median(latencies) if latencies else None,
            'p95': sorted(latencies)[max(0, int(len(latencies) * .95 + .9999) - 1)] if latencies else None,
            'note': '端到端HTTP案例时间（含排队/轮询/多请求），不是模型延迟或生产压测'}
        self.args.output.parent.mkdir(parents=True, exist_ok=True)
        self.args.output.write_text(json.dumps(self.output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def execute(self):
        self.setup()
        try:
            for case in self.cases:
                result = {key: case[key] for key in ['caseId', 'category', 'question', 'expectedMetric', 'expectedBehavior']}
                result.update({'checks': [], 'status': 'SKIP', 'durationMs': 0})
                if self.args.case and case['caseId'] not in self.args.case:
                    result['reason'] = '本次 --case 未选择该用例；不计为通过。'
                elif case['caseId'] in SKIPS:
                    result['reason'] = SKIPS[case['caseId']]
                else:
                    started = time.monotonic()
                    try:
                        if case['caseId'] in {'CL-023', 'CL-024', 'CL-025'}: self.security_oracle(case, result)
                        elif case['caseId'] == 'CL-034': self.currency_oracle(case, result)
                        elif case['caseId'] in {'CL-040', 'CL-041', 'CL-045'}: self.recovery_oracle(case, result)
                        else: self.semantic_oracle(case, result)
                        result['status'] = 'PASS' if result['checks'] and all(item['passed'] for item in result['checks']) else 'FAIL'
                    except Exception as error:
                        result['status'] = 'FAIL'; result['reason'] = str(error)
                        if isinstance(error, ApiError): result['httpError'] = {'status': error.status, 'code': error.body.get('code') if isinstance(error.body, dict) else None}
                    finally:
                        result['durationMs'] = round((time.monotonic() - started) * 1000)
                        self.cleanup_pending()
                self.output['cases'].append(result); self.save()
                failures = [item['name'] for item in result['checks'] if not item['passed']]
                print(result['caseId'], result['status'], ('; '.join(failures) or result.get('reason', ''))[:180], flush=True)
        finally:
            self.cleanup_pending()
            for member in self.members:
                try:
                    self.change_member(member, [], enabled=False)
                    self.output['cleanup'].append({'subjectId': member['subjectId'], 'status': 'DISABLED'})
                except Exception as error:
                    self.output['cleanup'].append({'subjectId': member['subjectId'], 'status': 'NEEDS_ATTENTION', 'reason': str(error)})
            self.output['finishedAt'] = datetime.now(timezone.utc).isoformat(); self.save()
        print(json.dumps(self.output['summary'], ensure_ascii=False), flush=True)
        return 1 if self.output['summary']['fail'] else 0


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default=os.environ.get('COMMERCE_EVALUATION_URL', 'http://127.0.0.1:8065/api/commerce/v1'))
    parser.add_argument('--username', default=os.environ.get('COMMERCE_ADMIN_USER', 'admin'))
    parser.add_argument('--password-env', default='COMMERCE_ADMIN_PASSWORD')
    parser.add_argument('--dataset-version', default=os.environ.get('COMMERCE_EVAL_DATASET', 'demo_20260921_v1'), help='明确期望的已发布快照版本；只允许黄金事实相同的副本，数值断言不变。')
    parser.add_argument('--env-file', type=Path, default=ROOT / '.commerce-local/development.env')
    parser.add_argument('--output', type=Path, default=ROOT / 'implementation/deterministic-evaluation.json')
    parser.add_argument('--case', action='append', help='仅重跑指定caseId，可重复；其他条目保留为SKIP。')
    parser.add_argument('--timeout', type=float, default=90, help='每个真实运行的等待秒数；不改变业务断言。')
    args = parser.parse_args()
    return Evaluation(args).execute()


if __name__ == '__main__':
    raise SystemExit(main())
