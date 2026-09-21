#!/usr/bin/env python3
"""Generate deterministic synthetic, non-personal business snapshots and UI fixtures.
Standard library only. The business dates are frozen; do not substitute datetime.now().
"""
from __future__ import annotations
import csv, hashlib, json
from pathlib import Path
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'fixtures'
VERSION='demo_20260921_v1'
DAY=date(2026,9,20)
START=DAY-timedelta(days=56)
SHOPS=[('s1','天猫旗舰店','TMALL'),('s2','抖音自营店','DOUYIN'),('s3','京东专卖店','JD')]
HEADERS={
 'stores':['tenant_id','dataset_version_id','store_id','name','platform','business_timezone'],
 'products':['tenant_id','dataset_version_id','store_id','product_id','sku_code','name','category'],
 'orders':['tenant_id','dataset_version_id','store_id','order_id','created_at_utc','source_status','expected_paid_cents'],
 'order_items':['tenant_id','dataset_version_id','store_id','order_id','item_id','product_id','quantity','allocated_paid_cents'],
 'payments':['tenant_id','dataset_version_id','store_id','payment_id','order_id','paid_at_utc','amount_cents'],
 'refunds':['tenant_id','dataset_version_id','store_id','refund_id','order_id','succeeded_at_utc','amount_cents'],
 'traffic_daily':['tenant_id','dataset_version_id','store_id','biz_date','visitor_sessions','quality_status'],
 'inventory_daily':['tenant_id','dataset_version_id','store_id','product_id','biz_date','closing_stock','stockout_minutes','quality_status']
}
def partition(total:int, count:int)->list[int]:
 q,r=divmod(total,count)
 return [q+(i<r) for i in range(count)]
def ts(d:date,hour:int,minutes:int=0)->str:
 # Inputs are Asia/Shanghai wall clock, UTC+8 in the fixed demo interval.
 return (datetime(d.year,d.month,d.day,hour)+timedelta(minutes=minutes,hours=-8)).strftime('%Y-%m-%d %H:%M:%S.000000')
def generate_tenant(tenant:str, other:bool=False):
 folder=OUT/'business'/tenant;folder.mkdir(parents=True,exist_ok=True)
 data={k:[] for k in HEADERS};daily=[]
 shops=SHOPS if not other else [('s1','隔离租户演示店','DEMO')]
 days=[START+timedelta(days=i) for i in range(57)] if not other else [DAY-timedelta(days=7),DAY]
 for sid,name,platform in shops:
  pre=[tenant,VERSION,sid]
  data['stores'].append(pre+[name,platform,'Asia/Shanghai'])
  names={'s1':['轻享保温杯 · 雾白','轻享保温杯 · 岩灰'],'s2':['轻量通勤包 · 经典','轻量通勤包 · 旅行'],'s3':['旅行收纳组 · Pro','旅行收纳组 · Lite']}[sid]
  for pi in [1,2]:data['products'].append(pre+[f'p{pi}',f'{sid.upper()}-00{pi}',names[pi-1],'日用生活'])
  previous_ids=[]
  for d in days:
   is_today=d==DAY
   if other:
    n,total,sessions,refund_total,ref_count=2,9999900,5,0,0
   elif is_today:
    n,total,sessions,refund_total,ref_count={
      's1':(1020,25500000,30000,1900000,80),
      's2':(500,14000000,9000,520000,25),
      's3':(322,9120000,7050,266000,15)}[sid]
   else:
    base_n,price,base_sessions,refund_total,ref_count={
      's1':(1440,25000,30000,1300000,80),
      's2':(500,30000,11000,500000,25),
      's3':(300,30000,9000,300000,15)}[sid]
    factor=[.96,.98,1.02,.97,1.04,1.03,1.0][d.weekday()]
    n=round(base_n*factor);total=n*price;sessions=round(base_sessions*factor)
   ids=[];amounts=partition(total,n)
   hot_count=612 if sid=='s1' and is_today and not other else (n*7)//10
   hot_total=0
   for i,amount in enumerate(amounts):
    oid=f'O{d:%Y%m%d}{i+1:06d}';ids.append(oid)
    prod='p1' if i<hot_count else 'p2'
    if prod=='p1':hot_total+=amount
    data['orders'].append(pre+[oid,ts(d,8,i%480),'CLOSED' if i%97==0 else 'PAID',amount])
    data['order_items'].append(pre+[oid,'1',prod,1,amount])
    data['payments'].append(pre+[f'P{d:%Y%m%d}{i+1:06d}',oid,ts(d,10,i%480),amount])
   if ref_count:
    # Old orders are refunded on the actual refund day. First date uses tail orders
    # to avoid double-refunding the next day's head-order refund set.
    refund_ids=previous_ids[:ref_count] if previous_ids else ids[-ref_count:]
    for i,(oid,amount) in enumerate(zip(refund_ids,partition(refund_total,ref_count))):
     data['refunds'].append(pre+[f'R{d:%Y%m%d}{i+1:05d}',oid,ts(d,20),amount])
   data['traffic_daily'].append(pre+[d.isoformat(),sessions,'COMPLETE'])
   for pi in [1,2]:
    stockout=480 if is_today and sid=='s1' and pi==1 and not other else (30 if sid=='s1' and pi==1 else 0)
    data['inventory_daily'].append(pre+[f'p{pi}',d.isoformat(),0 if stockout==480 else 120,stockout,'COMPLETE'])
   daily.append({'date':d.isoformat(),'storeId':sid,'paidGmvCents':str(total),'paidOrders':n,'refundCents':str(refund_total),'sessions':sessions,'hotSkuGmvCents':str(hot_total),'hotSkuStockoutMinutes':480 if is_today and sid=='s1' and not other else (30 if sid=='s1' else 0)})
   previous_ids=ids
 files=[]
 for name,rows in data.items():
  path=folder/f'{name}.csv'
  with path.open('w',encoding='utf-8',newline='') as f:
   w=csv.writer(f);w.writerow(HEADERS[name]);w.writerows(rows)
  files.append({'name':path.name,'rows':len(rows),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
 manifest={'schemaVersion':'1.0','tenantId':tenant,'datasetVersionId':VERSION,'synthetic':True,'currency':'CNY','timezone':'Asia/Shanghai','coverage':{'start':days[0].isoformat(),'endExclusive':(days[-1]+timedelta(days=1)).isoformat(),'completeBusinessDates':[d.isoformat() for d in days]},'businessWatermark':'2026-09-21T00:00:00+08:00','publishedAt':'2026-09-21T08:40:00+08:00','source':'deterministic synthetic generator','files':files}
 (folder/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 return daily,manifest

def main():
 OUT.mkdir(parents=True,exist_ok=True)
 daily,manifest=generate_tenant('t_demo')
 generate_tenant('t_other',True)
 metric_defs=[
 ('paid_gmv','支付 GMV','CNY_CENT','成功支付的商品实付金额；不含运费和税费。',['day','store','sku'],['payments','order_items'],'0','金额下降为风险'),
 ('paid_orders','支付订单数','COUNT','窗口内规范成功支付对应的订单数。',['day','store'],['payments'],'0','数量下降为风险'),
 ('aov','客单价','CNY_CENT','支付 GMV / 支付订单数；先汇总分子分母。',['day','store'],['payments'],'NULL','不等于商品单价'),
 ('refund_amount','成功退款额','CNY_CENT','以退款成功时间统计，可来自之前支付的订单。',['day','store'],['refunds'],'0','增加为风险'),
 ('net_receipts','净收款额','CNY_CENT','同期支付 GMV − 同期成功退款额；不是利润。',['day','store'],['payments','refunds'],'0','允许负值'),
 ('refund_intensity','退款金额强度','RATIO','同期成功退款额 / 同期支付 GMV；不是订单批次退款率。',['day','store'],['payments','refunds'],'NULL','允许超过100%'),
 ('visitor_sessions','访客会话数','COUNT','店铺日会话数求和；不是跨店去重人数。',['day','store'],['traffic_daily'],'0','仅完整业务日'),
 ('order_conversion_rate','支付订单转化比','RATIO','支付订单数 / 会话数；不是用户级转化率。',['day','store'],['payments','traffic_daily'],'NULL','变化用百分点展示')]
 metrics=[{'metricId':x[0],'version':1,'displayName':x[1],'unit':x[2],'definition':x[3],'supportedDimensions':x[4],'requiredFacts':x[5],'zeroDenominatorPolicy':x[6],'note':x[7],'compilerKey':x[0]+'_v1','state':'PUBLISHED'} for x in metric_defs]
 (ROOT/'contracts/metrics.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 def aggregate(d):
  a=[x for x in daily if x['date']==d]
  return {'paidGmvCents':str(sum(int(x['paidGmvCents']) for x in a)),'paidOrders':sum(x['paidOrders'] for x in a),'refundCents':str(sum(int(x['refundCents']) for x in a)),'sessions':sum(x['sessions'] for x in a)}
 gold={'synthetic':True,'tenantId':'t_demo','datasetVersionId':VERSION,'currentDate':DAY.isoformat(),'baselineDate':(DAY-timedelta(days=7)).isoformat(),'current':aggregate(DAY.isoformat()),'baseline':aggregate((DAY-timedelta(days=7)).isoformat()),'expected':{'gmvDeltaCents':'-11380000','s1DeltaCents':'-10500000','s2DeltaCents':'-1000000','s3DeltaCents':'120000','s1HotSkuDeltaCents':'-9900000','currentNetReceiptsCents':'45934000','baselineNetReceiptsCents':'57900000','currentConversionRatio':'0.04','baselineConversionRatio':'0.0448','s1OrderContributionCents':'-10500000','s1AovContributionCents':'0'}}
 (OUT/'golden.json').write_text(json.dumps(gold,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 payload={'synthetic':True,'tenant':'澄光生活 · 演示组织','tenantId':'t_demo','datasetVersionId':VERSION,'currentDate':DAY.isoformat(),'baselineDate':(DAY-timedelta(days=7)).isoformat(),'dataUpdatedAt':'2026-09-21 08:40','businessWatermark':'2026-09-20 已完整','timezone':'Asia/Shanghai','stores':[{'id':s,'name':n,'platform':p} for s,n,p in SHOPS],'metrics':metrics,'daily':daily,'fileRows':{f['name'].replace('.csv',''):f['rows'] for f in manifest['files']},'golden':gold}
 (OUT/'prototype-data.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 (ROOT/'prototype/data.js').write_text('window.COMMERCE_DATA = '+json.dumps(payload,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')
 print(json.dumps({'files':manifest['files'],'golden':gold},ensure_ascii=False))
if __name__=='__main__':main()
