from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[1]
def write(p,obj):
 (R/p).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
metrics=[m['metricId'] for m in json.loads((R/'contracts/metrics.json').read_text())]
obj=lambda props,req=None:{'type':'object','additionalProperties':False,'properties':props,'required':list(props) if req is None else req}
string={'type':'string'}
ident={'type':'string','minLength':1,'maxLength':96}
date={'type':'string','format':'date'}
dr=obj({'start':date,'endExclusive':date})
qs=obj({'metricIds':{'type':'array','items':{'enum':metrics},'minItems':1,'maxItems':8,'uniqueItems':True},'dimensions':{'type':'array','items':{'enum':['day','store','sku']},'uniqueItems':True,'maxItems':3},'dateRange':dr,'comparison':{'enum':['PREVIOUS_WEEK_SAME_DAYS','PREVIOUS_PERIOD','NONE']},'filters':{'type':'array','items':obj({'field':{'const':'productId'},'operator':{'const':'IN'},'values':{'type':'array','items':ident,'minItems':1,'maxItems':20,'uniqueItems':True}}),'maxItems':1},'limit':{'type':'integer','minimum':1,'maximum':1000}},['metricIds','dimensions','dateRange','comparison','filters','limit'])
qs['allOf']=[{'if':{'properties':{'dimensions':{'contains':{'const':'sku'}}}},'then':{'properties':{'metricIds':{'items':{'const':'paid_gmv'}},'dimensions':{'contains':{'const':'store'}}}}}]
qs['allOf'].append({'if':{'properties':{'filters':{'minItems':1}}},'then':{'properties':{'metricIds':{'items':{'const':'paid_gmv'}},'dimensions':{'contains':{'const':'store'}}}}})
write('contracts/query-spec.schema.json',{'$schema':'https://json-schema.org/draft/2020-12/schema','$id':'https://commercelens.invalid/schemas/query-spec','title':'Commerce QuerySpec (target contract)',**qs})
num=obj({'evidenceId':ident,'rowKey':string,'field':string,'transformId':{'enum':['IDENTITY','DELTA','RELATIVE_CHANGE','PP_DELTA','SHAPLEY_ORDERS','SHAPLEY_AOV']}})
claim=obj({'claimId':ident,'type':{'enum':['OBSERVATION','DECOMPOSITION','HYPOTHESIS','LIMITATION','RECOMMENDATION']},'text':{'type':'string','maxLength':2000},'evidenceRefs':{'type':'array','items':ident,'uniqueItems':True},'numericBindings':{'type':'array','items':num},'support':{'enum':['SUPPORTED','CONTRADICTED','INSUFFICIENT']}})
scope=obj({'tenantId':ident,'storeIds':{'type':'array','items':ident,'minItems':1,'uniqueItems':True},'authzVersion':{'type':'integer','minimum':1}})
report=obj({'reportId':ident,'version':{'type':'integer','minimum':1},'runId':ident,'title':string,'status':{'enum':['SUCCEEDED','PARTIAL']},'createdAt':{'type':'string','format':'date-time'},'scope':scope,'dateRange':dr,'comparisonRange':dr,'datasetVersionId':ident,'metricManifestHash':string,'claims':{'type':'array','items':claim,'minItems':1},'limitations':{'type':'array','items':string},'synthetic':{'type':'boolean'}})
write('contracts/report.schema.json',{'$schema':'https://json-schema.org/draft/2020-12/schema','$id':'https://commercelens.invalid/schemas/report','title':'Structured diagnostic report',**report})
eventtypes=['run.created','run.state.changed','plan.ready','approval.required','step.started','step.summary','evidence.ready','report.ready','run.completed','run.failed','run.cancelled','access.revoked']
event=obj({'schemaVersion':{'const':'1.0'},'runId':ident,'sequence':{'type':'integer','minimum':1},'type':{'enum':eventtypes},'occurredAt':{'type':'string','format':'date-time'},'payload':{'type':'object','description':'Payload varies by type; no raw private reasoning or unscoped query rows.'}})
write('contracts/run-event.schema.json',{'$schema':'https://json-schema.org/draft/2020-12/schema',**event})
S={}
S['Error']=obj({'code':string,'message':string,'requestId':ident,'details':{'type':'object'}},['code','message','requestId'])
S['DateRange']=dr;S['QuerySpec']=qs;S['Scope']=scope;S['Report']=report;S['RunEvent']=event
ref=lambda n:{'$ref':'#/components/schemas/'+n}
S['Store']=obj({'storeId':ident,'name':string,'platform':string})
S['Me']=obj({'subjectId':ident,'tenantId':ident,'displayName':string,'roles':{'type':'array','items':{'enum':['TENANT_ADMIN','OPS_MANAGER','STORE_OPERATOR','VIEWER']}},'authzVersion':{'type':'integer'},'stores':{'type':'array','items':ref('Store')}})
S['Quality']=obj({'status':{'enum':['COMPLETE','PARTIAL','STALE','INVALID']},'businessWatermark':{'type':'string','format':'date-time'},'publishedAt':{'type':'string','format':'date-time'},'warnings':{'type':'array','items':string}})
S['Metric']=obj({'metricId':{'enum':metrics},'version':{'type':'integer','minimum':1},'displayName':string,'unit':{'enum':['CNY_CENT','COUNT','RATIO']},'definition':string,'supportedDimensions':{'type':'array','items':{'enum':['day','store','sku']}},'requiredFacts':{'type':'array','items':string},'zeroDenominatorPolicy':{'enum':['0','NULL']},'compilerKey':string,'note':string,'state':{'const':'PUBLISHED'}},['metricId','version','displayName','unit','definition','supportedDimensions','requiredFacts','zeroDenominatorPolicy','compilerKey','state'])
# JSON scalar values are decimal strings. Structured keys carry dimensions, no arbitrary untyped rows.
S['Cell']=obj({'field':string,'value':{'type':['string','null'],'pattern':'^-?[0-9]+(\\.[0-9]+)?$'},'unit':{'enum':['CNY_CENT','COUNT','RATIO','PP']},'reason':{'type':['string','null']}},['field','value','unit'])
S['ResultRow']=obj({'rowKey':string,'dimensions':{'type':'object','additionalProperties':string},'cells':{'type':'array','items':ref('Cell')}})
S['Column']=obj({'field':string,'label':string,'unit':{'enum':['CNY_CENT','COUNT','RATIO','PP','DIMENSION']}})
S['QueryResult']=obj({'queryArtifactId':ident,'evidenceId':ident,'scope':ref('Scope'),'dateRange':ref('DateRange'),'comparisonRange':{'anyOf':[ref('DateRange'),{'type':'null'}]},'datasetVersionId':ident,'metricVersions':{'type':'object','additionalProperties':{'type':'integer'}},'quality':ref('Quality'),'columns':{'type':'array','items':ref('Column')},'rows':{'type':'array','items':ref('ResultRow')},'totals':ref('ResultRow'),'rowCount':{'type':'integer','minimum':0},'truncated':{'type':'boolean'}})
S['Overview']=obj({'scope':ref('Scope'),'dateRange':ref('DateRange'),'comparisonRange':ref('DateRange'),'datasetVersionId':ident,'quality':ref('Quality'),'totals':ref('ResultRow'),'baselineTotals':ref('ResultRow'),'trend':{'type':'array','items':ref('ResultRow')},'stores':{'type':'array','items':ref('ResultRow')}})
S['PlanAction']=obj({'actionId':ident,'toolName':{'enum':['resolve_metric','inspect_data_quality','query_metrics','compare_segments','decompose_gmv','inspect_inventory_signal','lookup_business_definition','finalize_report']},'purpose':{'type':'string','maxLength':500},'maxCalls':{'type':'integer','minimum':1,'maximum':12}})
S['Plan']=obj({'planVersion':{'type':'integer','minimum':1},'planHash':string,'dateRange':ref('DateRange'),'scope':ref('Scope'),'actions':{'type':'array','items':ref('PlanAction')},'maxToolCalls':{'type':'integer','maximum':12},'maxTokens':{'type':'integer','maximum':30000},'expiresAt':{'type':'string','format':'date-time'}})
statuses=['QUEUED','PLANNING','WAITING_APPROVAL','RUNNING','CANCEL_REQUESTED','SUCCEEDED','PARTIAL','FAILED','CANCELLED','EXPIRED','ACCESS_REVOKED']
S['Run']=obj({'runId':ident,'conversationId':ident,'status':{'enum':statuses},'runVersion':{'type':'integer','minimum':1},'question':string,'scope':ref('Scope'),'datasetVersionId':ident,'metricManifestHash':string,'plan':{'anyOf':[ref('Plan'),{'type':'null'}]},'latestSequence':{'type':'integer','minimum':0},'eventsUrl':string,'reportId':{'type':['string','null']},'createdAt':{'type':'string','format':'date-time'}},['runId','conversationId','status','runVersion','question','scope','datasetVersionId','metricManifestHash','plan','latestSequence','eventsUrl','createdAt'])
S['CreateRun']=obj({'question':{'type':'string','minLength':1,'maxLength':2000},'requestedStoreIds':{'type':'array','items':ident,'minItems':1,'maxItems':100,'uniqueItems':True},'dateRange':ref('DateRange'),'comparison':{'enum':['PREVIOUS_WEEK_SAME_DAYS','PREVIOUS_PERIOD']},'requireApproval':{'type':'boolean','default':True}},['question','requestedStoreIds','dateRange','comparison','requireApproval'])
S['Approval']=obj({'decision':{'enum':['APPROVE','REJECT']},'planVersion':{'type':'integer','minimum':1},'planHash':string,'expectedRunVersion':{'type':'integer','minimum':1},'comment':{'type':'string','maxLength':1000}},['decision','planVersion','planHash','expectedRunVersion'])
S['Evidence']=obj({'evidenceId':ident,'runId':{'type':['string','null']},'kind':{'enum':['QUERY_RESULT','DECOMPOSITION','INVENTORY_SIGNAL']},'datasetVersionId':ident,'metricManifestHash':string,'scope':ref('Scope'),'result':ref('QueryResult'),'sqlTemplate':{'type':['string','null']},'redactedParameters':{'type':'object'},'resultHash':string,'durationMs':{'type':'integer','minimum':0},'createdAt':{'type':'string','format':'date-time'}})
S['AnomalyRuleInput']=obj({'name':{'type':'string','minLength':1,'maxLength':100},'metricId':{'enum':['paid_gmv','paid_orders','refund_amount','order_conversion_rate']},'storeIds':{'type':'array','items':ident,'minItems':1},'direction':{'enum':['UP','DOWN']},'baseline':{'const':'SAME_WEEKDAY_MEDIAN_8'},'relativeThreshold':{'type':['string','null']},'absoluteThreshold':{'type':'string'},'unit':{'enum':['CNY_CENT','COUNT','PP']},'cooldownHours':{'type':'integer','minimum':1,'maximum':168},'enabled':{'type':'boolean'}})
S['AnomalyRule']={'allOf':[ref('AnomalyRuleInput')], 'description':'Response: rule definition and identity are separate fields to preserve closed input schema.'}
# Replace with a wrapper instead of extending additionalProperties:false.
S['AnomalyRule']=obj({'ruleId':ident,'version':{'type':'integer','minimum':1},'definition':ref('AnomalyRuleInput')})
S['Anomaly']=obj({'anomalyId':ident,'ruleId':ident,'ruleVersion':{'type':'integer'},'status':{'enum':['OPEN','ACKNOWLEDGED','RESOLVED','SUPPRESSED']},'severity':{'enum':['INFO','WARNING','CRITICAL']},'scope':ref('Scope'),'metricId':{'enum':metrics},'window':ref('DateRange'),'referenceDates':{'type':'array','items':date},'current':ref('Cell'),'baseline':ref('Cell'),'datasetVersionId':ident,'reason':string})
S['Dataset']=obj({'datasetVersionId':ident,'status':{'enum':['DRAFT','VALIDATING','PUBLISHED','REJECTED','RETIRED']},'coverage':ref('DateRange'),'quality':ref('Quality'),'tables':{'type':'array','items':obj({'name':string,'rowCount':{'type':'integer','minimum':0}})}})
S['Ingestion']=obj({'jobId':ident,'version':{'type':'integer','minimum':1},'status':{'enum':['UPLOADED','VALIDATING','READY_TO_PUBLISH','PUBLISHED','REJECTED','FAILED']},'datasetVersionId':ident,'filesValidated':{'type':'integer','minimum':0,'maximum':8},'errors':{'type':'array','items':obj({'file':string,'line':{'type':'integer','minimum':0},'code':string,'message':string})}})
def env_schema(s):return obj({'requestId':ident,'data':s})
def response(s,desc='成功'):
 return {'description':desc,'content':{'application/json':{'schema':env_schema(s)}}}
def list_schema(s):return obj({'items':{'type':'array','items':s},'nextCursor':{'type':['string','null']}})
def param(name,loc,s,required=False,description=''):
 return {'name':name,'in':loc,'required':required,'schema':s,**({'description':description} if description else {})}
csrf=param('X-CSRF-Token','header',ident,True,'Required with authenticated same-origin mutation.')
idem=param('Idempotency-Key','header',ident,True,'Same tenant/subject/operation/key with different body returns 409.')
page=[param('cursor','query',string),param('limit','query',{'type':'integer','minimum':1,'maximum':100,'default':20})]
P={}
def op(path,method,operationId,summary,result=None,body=None,params=None,code='200',mutate=False,custom=None):
 x={'operationId':operationId,'summary':summary,'tags':[path.strip('/').split('/')[0]],'responses':{code:response(result or {'type':'null'}),'default':{'description':'Error: 400/401/403/404/409/410/422/429/503 as documented','content':{'application/json':{'schema':ref('Error')}}}}}
 pp=list(params or [])
 for name in ['runId','reportId','evidenceId','ruleId','anomalyId','jobId']:
  if '{'+name+'}' in path:pp.append(param(name,'path',ident,True))
 if mutate:pp.append(csrf)
 if pp:x['parameters']=pp
 if body:x['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
 if custom:x.update(custom)
 P.setdefault(path,{})[method]=x
op('/me','get','getMe','当前身份与授权范围',ref('Me'))
op('/overview','get','getOverview','授权范围的经营总览',ref('Overview'),params=[param('storeIds','query',{'type':'array','items':ident},True),param('start','query',date,True),param('endExclusive','query',date,True),param('comparison','query',{'enum':['PREVIOUS_WEEK_SAME_DAYS','PREVIOUS_PERIOD']},True)])
op('/metrics','get','listMetrics','已发布指标目录',{'type':'array','items':ref('Metric')})
op('/queries','post','queryMetrics','执行严格受控的指标查询',ref('QueryResult'),body=obj({'requestedStoreIds':{'type':'array','items':ident,'minItems':1},'spec':ref('QuerySpec')}),mutate=True)
op('/runs','post','createRun','创建诊断运行，连接与执行独立',ref('Run'),body=ref('CreateRun'),params=[idem],code='202',mutate=True)
op('/runs','get','listRuns','当前用户可见运行',list_schema(ref('Run')),params=page+[param('status','query',{'enum':statuses})])
op('/runs/{runId}','get','getRun','运行快照',ref('Run'))
op('/runs/{runId}/approval','post','approveRun','批准或拒绝指定版本计划',ref('Run'),body=ref('Approval'),mutate=True)
op('/runs/{runId}/cancel','post','cancelRun','幂等请求取消',ref('Run'),body=obj({'expectedRunVersion':{'type':'integer','minimum':1}}),code='202',mutate=True)
op('/runs/{runId}/events','get','subscribeRunEvents','SSE订阅与重放，不启动任务',params=[param('Last-Event-ID','header',{'type':'string','pattern':'^[0-9]+$'}),param('after','query',{'type':'integer','minimum':0})])
P['/runs/{runId}/events']['get']['responses']['200']={'description':'Ordered persistent events; heartbeat comments have no ID. Event payload follows RunEvent. Expired cursor: 410.','content':{'text/event-stream':{'schema':{'type':'string'},'example':'id: 1\nevent: run.created\ndata: {"schemaVersion":"1.0","runId":"r1","sequence":1,"type":"run.created","occurredAt":"2026-09-21T01:00:00Z","payload":{}}\n\n'}}}
op('/evidence/{evidenceId}','get','getEvidence','读取当前仍有权访问的证据',ref('Evidence'))
op('/reports','get','listReports','当前可见报告',list_schema(ref('Report')),params=page)
op('/reports/{reportId}','get','getReport','读取不可变报告',ref('Report'))
op('/reports/{reportId}/export','get','exportReport','重新鉴权后导出Markdown')
P['/reports/{reportId}/export']['get']['responses']['200']={'description':'Download report with Content-Disposition attachment. Export is audited.','content':{'text/markdown':{'schema':{'type':'string'}}}}
op('/anomaly-rules','get','listRules','规则列表',list_schema(ref('AnomalyRule')),params=page)
op('/anomaly-rules','post','createRule','管理员创建规则',ref('AnomalyRule'),body=ref('AnomalyRuleInput'),mutate=True,code='201')
op('/anomaly-rules/{ruleId}','patch','updateRule','CAS更新规则并产生新版本',ref('AnomalyRule'),body=obj({'expectedVersion':{'type':'integer','minimum':1},'definition':ref('AnomalyRuleInput')}),mutate=True)
op('/anomalies','get','listAnomalies','业务异常列表',list_schema(ref('Anomaly')),params=page)
op('/anomalies/{anomalyId}/acknowledge','post','acknowledgeAnomaly','确认知晓，不直接解除异常',ref('Anomaly'),body=obj({'comment':{'type':'string','maxLength':1000}},[]),mutate=True)
op('/datasets','get','listDatasets','数据版本与完整性',list_schema(ref('Dataset')),params=page)
op('/ingestions','post','createIngestion','上传标准CSV ZIP，不执行SQL',ref('Ingestion'),params=[idem],mutate=True,code='202')
P['/ingestions']['post']['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':obj({'archive':{'type':'string','format':'binary'}})}}}
op('/ingestions/{jobId}','get','getIngestion','查询导入校验进度',ref('Ingestion'))
op('/ingestions/{jobId}/publish','post','publishIngestion','管理员CAS发布不可变快照',ref('Dataset'),body=obj({'expectedVersion':{'type':'integer','minimum':1}}),mutate=True)
api={'openapi':'3.1.0','info':{'title':'CommerceLens 电商经营分析 API','version':'1.0.0-design','description':'Proposed extension contract, not an upstream API or an implemented production backend. Monetary units: integer cents encoded as strings. Business timezone: Asia/Shanghai.'},'servers':[{'url':'/api/commerce/v1'}],'security':[{'sessionCookie':[]}],'paths':P,'components':{'securitySchemes':{'sessionCookie':{'type':'apiKey','in':'cookie','name':'CL_SESSION','description':'HttpOnly Secure same-origin authenticated session. Mutations additionally require CSRF header.'}},'schemas':S}}
(R/'contracts/openapi.yaml').write_text(yaml.safe_dump(api,allow_unicode=True,sort_keys=False,width=120),encoding='utf-8')
example={'metricIds':['paid_gmv','paid_orders','aov'],'dimensions':['store'],'dateRange':{'start':'2026-09-20','endExclusive':'2026-09-21'},'comparison':'PREVIOUS_WEEK_SAME_DAYS','filters':[],'limit':100}
write('contracts/query-example.json',example)
print('operations',sum(len(v) for v in P.values()),'schemas',len(S))
