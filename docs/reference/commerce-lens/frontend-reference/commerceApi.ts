import type { Approval, CreateRun, Envelope, Run } from './commerce';

export class CommerceApiError extends Error {
  constructor(public readonly status: number, public readonly code: string,
    message: string, public readonly requestId?: string) { super(message); this.name='CommerceApiError'; }
}
/** Never derive tenant or authorization scope from an LLM response. */
export function createCommerceApi(csrfToken: () => string | undefined) {
  const base='/api/commerce/v1';
  async function request<T>(path:string, options:RequestInit={}):Promise<T> {
    const headers=new Headers(options.headers);
    headers.set('Accept','application/json');
    if(options.body) headers.set('Content-Type','application/json');
    if(options.method && options.method!=='GET') {
      const token=csrfToken();
      if(!token) throw new CommerceApiError(0,'CSRF_UNAVAILABLE','Missing CSRF token');
      headers.set('X-CSRF-Token',token);
    }
    const response=await fetch(base+path,{...options,headers,credentials:'same-origin'});
    let body: unknown;
    try { body=await response.json(); }
    catch { throw new CommerceApiError(response.status,'INVALID_RESPONSE','Expected a JSON response'); }
    if(!response.ok) {
      const e=body as {code?:string; message?:string; requestId?:string};
      throw new CommerceApiError(response.status,e.code||'HTTP_ERROR',e.message||'Request failed',e.requestId);
    }
    const result=body as Envelope<T>;
    if(!result || typeof result.requestId!=='string' || !('data' in result))
      throw new CommerceApiError(response.status,'INVALID_ENVELOPE','Invalid API envelope');
    return result.data;
  }
  return {
    getRun:(id:string,signal?:AbortSignal)=>request<Run>(`/runs/${encodeURIComponent(id)}`,{signal}),
    createRun:(body:CreateRun,key:string)=>request<Run>('/runs',{
      method:'POST',headers:{'Idempotency-Key':key},body:JSON.stringify(body)}),
    approveRun:(id:string,body:Approval)=>request<Run>(`/runs/${encodeURIComponent(id)}/approval`,{
      method:'POST',body:JSON.stringify(body)}),
    cancelRun:(id:string,expectedRunVersion:number)=>request<Run>(`/runs/${encodeURIComponent(id)}/cancel`,{
      method:'POST',body:JSON.stringify({expectedRunVersion})}),
    /** Auth re-evaluated for every report/evidence fetch. */
    request,
  };
}
export type CommerceApi=ReturnType<typeof createCommerceApi>;
