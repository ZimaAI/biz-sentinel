#!/usr/bin/env python3
"""Static schema checks; not proof that the unimplemented server honors them."""
from pathlib import Path
import json,copy,re
import yaml
from jsonschema import Draft202012Validator,FormatChecker
R=Path(__file__).resolve().parents[1]
checks=[]
def check(name,condition):
    checks.append({'name':name,'passed':bool(condition)})
    if not condition:raise AssertionError(name)
schemas={p.stem:json.loads(p.read_text()) for p in (R/'contracts').glob('*.schema.json')}
for name,s in schemas.items():
    Draft202012Validator.check_schema(s);check(name+' meta-schema valid',True)
api=yaml.safe_load((R/'contracts/openapi.yaml').read_text())
check('OpenAPI 3.1.0',api['openapi']=='3.1.0')
ops=[]
for path,methods in api['paths'].items():
    for method,op in methods.items():
        if method not in ['get','post','put','patch','delete']:continue
        ops.append(op['operationId'])
        parameters=op.get('parameters',[])
        keys=[(x['in'],x['name']) for x in parameters]
        check(method+' '+path+' parameter uniqueness',len(keys)==len(set(keys)))
        for p in re.findall(r'{(\w+)}',path):check(path+' '+p+' required',any(x['in']=='path' and x['name']==p and x['required'] for x in parameters))
        if method in ['post','patch','put','delete']:check(op['operationId']+' CSRF header',('header','X-CSRF-Token') in keys)
check('23 unique operation IDs',len(ops)==len(set(ops))==23)
def walk(obj):
    if isinstance(obj,dict):
        if '$ref' in obj:
            ref=obj['$ref'];check('local ref '+ref,ref.startswith('#/'))
            val=api
            for bit in ref[2:].split('/'):val=val[bit]
        for v in obj.values():walk(v)
    elif isinstance(obj,list):
        for v in obj:walk(v)
walk(api)
for name,s in api['components']['schemas'].items():
    Draft202012Validator.check_schema(s);check(name+' valid schema',True)
metric_validator=Draft202012Validator(api['components']['schemas']['Metric'])
for m in json.loads((R/'contracts/metrics.json').read_text()):
    metric_validator.validate(m);check(m['metricId']+' definition valid',True)
v=Draft202012Validator(schemas['query-spec.schema'],format_checker=FormatChecker())
q=json.loads((R/'contracts/query-example.json').read_text());v.validate(q);check('query example valid',True)
def negative(name,mutate):
    bad=copy.deepcopy(q);mutate(bad);check(name+' rejected by JSON Schema',not v.is_valid(bad))
negative('arbitrary SQL',lambda x:x.update(sql='SELECT * FROM cl_payment'))
negative('client tenant inside QuerySpec',lambda x:x.update(tenantId='t_other'))
negative('unknown metric',lambda x:x.update(metricIds=['profit']))
negative('SKU refund',lambda x:x.update(metricIds=['refund_amount'],dimensions=['store','sku']))
negative('SKU without store grain',lambda x:x.update(metricIds=['paid_gmv'],dimensions=['sku']))
negative('duplicate metrics',lambda x:x.update(metricIds=['paid_gmv','paid_gmv']))
negative('excess rows',lambda x:x.update(limit=1001))
negative('invalid date',lambda x:x['dateRange'].update(start='2026-02-30'))
negative('product filter used with refunds',lambda x:x.update(metricIds=['refund_amount'],filters=[{'field':'productId','operator':'IN','values':['p1']}]))
qsku=copy.deepcopy(q);qsku.update(metricIds=['paid_gmv'],dimensions=['store','sku']);v.validate(qsku);check('supported SKU GMV valid',True)
for file,sch in [('report-example.json','report.schema'),('run-event-example.json','run-event.schema')]:
    instance=json.loads((R/'contracts'/file).read_text());Draft202012Validator(schemas[sch],format_checker=FormatChecker()).validate(instance);check(file+' valid',True)
check('8 business table DDL declarations',len(re.findall(r'CREATE TABLE cl_', (R/'database/001-business.sql').read_text()))==8)
check('15 management table DDL declarations',len(re.findall(r'CREATE TABLE cl_', (R/'database/002-management.sql').read_text()))==15)
result={'kind':'static-contract-checks','passed':len(checks),'failed':0,'checks':checks,'notTested':['OpenAPI server conformance','MySQL DDL execution','Nuxt typecheck','Java compile','runtime permission enforcement']}
(R/'evaluation/contract-validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':len(checks),'failed':0,'operations':len(ops)},ensure_ascii=False))
