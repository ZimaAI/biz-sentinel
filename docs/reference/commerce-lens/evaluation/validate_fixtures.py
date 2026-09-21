#!/usr/bin/env python3
"""Independent synthetic CSV validation. Standard library, not Java/MySQL tests."""
from __future__ import annotations
import csv,hashlib,json,sqlite3
from pathlib import Path
from collections import defaultdict
from datetime import datetime,timedelta,date
from decimal import Decimal,getcontext
getcontext().prec=38
ROOT=Path(__file__).resolve().parents[1]
checks=[]
def check(name,condition,detail=''):
 checks.append({'name':name,'passed':bool(condition),'detail':detail})
 if not condition:raise AssertionError(name+': '+detail)
def read(path):
 with path.open(encoding='utf-8',newline='') as f:return list(csv.DictReader(f))
def orderkey(r):return tuple(r[k] for k in ('tenant_id','dataset_version_id','store_id','order_id'))
def bizday(timestamp):return (datetime.fromisoformat(timestamp)+timedelta(hours=8)).date().isoformat()
all_tenants={}
for tenant in ('t_demo','t_other'):
 folder=ROOT/'fixtures/business'/tenant
 mf=json.loads((folder/'manifest.json').read_text());data={}
 for f in mf['files']:
  path=folder/f['name'];rows=read(path);data[path.stem]=rows
  check(f'{tenant}/{path.stem}: checksum',hashlib.sha256(path.read_bytes()).hexdigest()==f['sha256'])
  check(f'{tenant}/{path.stem}: row count',len(rows)==f['rows'])
  check(f'{tenant}/{path.stem}: tenant/version',all(r['tenant_id']==tenant and r['dataset_version_id']==mf['datasetVersionId'] for r in rows))
 check(f'{tenant}: 8 canonical files',len(data)==8)
 stores={r['store_id'] for r in data['stores']}
 check(f'{tenant}: store references',all(r['store_id'] in stores for rows in data.values() for r in rows))
 products={(r['store_id'],r['product_id']) for r in data['products']}
 orders={orderkey(r):r for r in data['orders']}
 check(f'{tenant}: order composite uniqueness',len(orders)==len(data['orders']))
 pays={orderkey(r):r for r in data['payments']}
 check(f'{tenant}: one canonical payment/order',len(pays)==len(data['payments']))
 check(f'{tenant}: payment references/amounts',all(k in orders and int(p['amount_cents'])==int(orders[k]['expected_paid_cents']) and int(p['amount_cents'])>=0 for k,p in pays.items()))
 item_sum=defaultdict(int)
 for r in data['order_items']:
  k=orderkey(r);assert k in orders
  assert (r['store_id'],r['product_id']) in products
  assert int(r['allocated_paid_cents'])>=0 and int(r['quantity'])>0
  item_sum[k]+=int(r['allocated_paid_cents'])
 check(f'{tenant}: items reconcile all orders',set(item_sum)==set(orders) and all(item_sum[k]==int(r['expected_paid_cents']) for k,r in orders.items()))
 refund_sum=defaultdict(int)
 for r in data['refunds']:
  k=orderkey(r);assert k in pays and int(r['amount_cents'])>=0
  assert datetime.fromisoformat(r['succeeded_at_utc'])>=datetime.fromisoformat(pays[k]['paid_at_utc'])
  refund_sum[k]+=int(r['amount_cents'])
 check(f'{tenant}: cumulative refund capped',all(v<=int(pays[k]['amount_cents']) for k,v in refund_sum.items()))
 dates=set(mf['coverage']['completeBusinessDates'])
 check(f'{tenant}: daily source coverage', {(r['store_id'],r['biz_date']) for r in data['traffic_daily']}=={(s,d) for s in stores for d in dates})
 check(f'{tenant}: inventory bounds',all(0<=int(r['stockout_minutes'])<=1440 and (r['store_id'],r['product_id']) in products for r in data['inventory_daily']))
 check(f'{tenant}: immutable per-table composite IDs', all(len(rows)==len({tuple(r[k] for k in keys) for r in rows}) for name,keys in {'stores':['store_id'],'products':['store_id','product_id'],'order_items':['store_id','order_id','item_id'],'payments':['store_id','payment_id'],'refunds':['store_id','refund_id'],'traffic_daily':['store_id','biz_date'],'inventory_daily':['store_id','product_id','biz_date']}.items() for rows in [data[name]]))
 all_tenants[tenant]=data
# Independent event-time aggregates; no import of fixture generator or UI functions.
data=all_tenants['t_demo'];sums=defaultdict(lambda:defaultdict(int));pay_dates={}
for r in data['payments']:
 day=bizday(r['paid_at_utc']);k=(r['store_id'],day)
 sums[k]['gmv']+=int(r['amount_cents']);sums[k]['orders']+=1;pay_dates[orderkey(r)]=day
for r in data['refunds']:sums[(r['store_id'],bizday(r['succeeded_at_utc']))]['refund']+=int(r['amount_cents'])
for r in data['traffic_daily']:sums[(r['store_id'],r['biz_date'])]['sessions']+=int(r['visitor_sessions'])
for r in data['order_items']:
 if r['product_id']=='p1':sums[(r['store_id'],pay_dates[orderkey(r)])]['hot']+=int(r['allocated_paid_cents'])
ui=json.loads((ROOT/'fixtures/prototype-data.json').read_text());g=json.loads((ROOT/'fixtures/golden.json').read_text())
check('all 171 UI daily records equal independent CSV aggregates',all(all(int(r[u])==sums[(r['storeId'],r['date'])][k] for u,k in [('paidGmvCents','gmv'),('paidOrders','orders'),('refundCents','refund'),('sessions','sessions'),('hotSkuGmvCents','hot')]) for r in ui['daily']) and len(ui['daily'])==171)
def total(day):return {key:sum(row[key] for (store,d),row in sums.items() if d==day) for key in ('gmv','orders','refund','sessions','hot')}
c=total(g['currentDate']);b=total(g['baselineDate'])
check('golden current GMV fixed 486200.00 CNY',c['gmv']==48620000)
check('golden baseline GMV fixed 600000.00 CNY',b['gmv']==60000000)
for key,expected in [('orders',1842),('refund',2686000),('sessions',46050)]:check('current '+key,c[key]==expected)
check('net receipts correct',c['gmv']-c['refund']==45934000 and b['gmv']-b['refund']==57900000)
check('aggregate order conversion ratios',Decimal(c['orders'])/c['sessions']==Decimal('0.04') and Decimal(b['orders'])/b['sessions']==Decimal('0.0448'))
check('refund intensity not cohort rate',Decimal(c['refund'])/c['gmv']>Decimal('0.055'))
contrib={s:sums[(s,g['currentDate'])]['gmv']-sums[(s,g['baselineDate'])]['gmv'] for s in ['s1','s2','s3']}
check('store contributions exact',contrib=={'s1':-10500000,'s2':-1000000,'s3':120000})
check('contributions reconcile total',sum(contrib.values())==c['gmv']-b['gmv']==-11380000)
check('core SKU delta -99000.00 CNY',sums[('s1',g['currentDate'])]['hot']-sums[('s1',g['baselineDate'])]['hot']==-9900000)
n0,n1=Decimal(b['orders']),Decimal(c['orders']);a0,a1=Decimal(b['gmv'])/n0,Decimal(c['gmv'])/n1
order_part=(n1-n0)*(a0+a1)/2;aov_part=(a1-a0)*(n0+n1)/2
check('symmetric decomposition conservation',abs(order_part+aov_part-Decimal(c['gmv']-b['gmv']))<Decimal('0.00000000000001'))
check('closed historical orders counted',any(r['source_status']=='CLOSED' and orderkey(r) in pay_dates for r in data['orders']))
check('refund uses success time not pay time',all(bizday(r['succeeded_at_utc'])!=pay_dates[orderkey(r)] for r in data['refunds'] if bizday(r['succeeded_at_utc'])==g['currentDate']))
check('fixture contains repeated external IDs across tenants',bool({r['order_id'] for r in all_tenants['t_other']['orders']}&{r['order_id'] for r in data['orders']}),'Presence of collision fixtures does not test server authorization.')
check('fixed Shanghai left-inclusive right-exclusive boundaries',bizday('2026-09-19 16:00:00')=='2026-09-20' and bizday('2026-09-20 15:59:59.999999')=='2026-09-20' and bizday('2026-09-20 16:00:00')=='2026-09-21')
# Small separate adversarial arithmetic fixtures, intentionally not production integration tests.
con=sqlite3.connect(':memory:');con.executescript('CREATE TABLE p(id TEXT, amount INTEGER); CREATE TABLE i(oid TEXT, amount INTEGER); CREATE TABLE r(oid TEXT, amount INTEGER); INSERT INTO p VALUES("o1",10000); INSERT INTO i VALUES("o1",6000),("o1",4000); INSERT INTO r VALUES("o1",1000),("o1",2000);')
wrong=con.execute('SELECT SUM(p.amount),SUM(r.amount) FROM p JOIN i ON p.id=i.oid JOIN r ON p.id=r.oid').fetchone()
check('fan-out counterexample detected',wrong==(40000,6000))
correct=con.execute('SELECT (SELECT SUM(amount) FROM p),(SELECT SUM(amount) FROM r)').fetchone()
check('independent fact aggregation avoids fan-out',correct==(10000,3000))
ratio=lambda x,y:None if y==0 else Decimal(x)/Decimal(y)
check('zero denominator yields NULL not zero',ratio(0,0) is None and ratio(100,0) is None)
check('period refund intensity can exceed 100%',ratio(20000,10000)==Decimal(2))
check('net receipts may be negative',10000-20000==-10000)
check('weighted ratios not average of ratios',ratio(1+9,2+90)!= (ratio(1,2)+ratio(9,90))/2)
check('offset contributions reconcile with positive store',sum([-10000,-2000,4000])==-8000 and Decimal(-10000)/-8000>1)
reference_dates=[date.fromisoformat(g['currentDate'])-timedelta(days=7*x) for x in range(1,9)]
check('8 full weekday references exist',all(total(d.isoformat())['gmv']==60000000 for d in reference_dates))
check('MAD zero fixture present',len({total(d.isoformat())['gmv'] for d in reference_dates})==1)
result={'kind':'synthetic-data-and-arithmetic','passed':len(checks),'failed':0,'checks':checks,'notTested':['Java','MySQL','live platform','authorization middleware','real LLM'],'rowCounts':{tenant:{k:len(v) for k,v in t.items()} for tenant,t in all_tenants.items()},'current':c,'baseline':b,'decompositionCents':{'orders':str(order_part),'aov':str(aov_part)}}
(ROOT/'evaluation/fixture-validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':len(checks),'failed':0,'current':c,'csvRows':sum(len(v) for t in all_tenants.values() for v in t.values())},ensure_ascii=False))
