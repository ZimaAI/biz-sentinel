import { onBeforeUnmount, ref } from 'vue';
import type { CommerceApi } from './commerceApi';
import { CommerceApiError } from './commerceApi';
import type { Run, RunEvent } from './commerce';

const TYPES=['run.created','run.state.changed','plan.ready','approval.required','step.started',
  'step.summary','evidence.ready','report.ready','run.completed','run.failed','run.cancelled','access.revoked'];
const TERMINAL=new Set(['SUCCEEDED','PARTIAL','FAILED','CANCELLED','EXPIRED','ACCESS_REVOKED']);
/** Browser-only reference. Integrate and test within the upstream Nuxt app. */
export function useRunEvents(api:CommerceApi, handlers:{
  onEvent:(event:RunEvent)=>void; onSnapshot:(run:Run)=>void; clearSensitive:()=>void;
}) {
  const connected=ref(false),error=ref<string|null>(null),lastSequence=ref(0);
  let source:EventSource|null=null, retry:ReturnType<typeof setTimeout>|undefined;
  let generation=0,runId='',attempts=0,recovering=false;
  function stop() {
    generation++; source?.close(); source=null; connected.value=false;
    clearTimeout(retry); retry=undefined; recovering=false;
  }
  function connect(myGeneration:number) {
    if(myGeneration!==generation)return;
    // Relative, same-origin, token-free URL. Explicit after survives a new EventSource instance.
    source=new EventSource(`/api/commerce/v1/runs/${encodeURIComponent(runId)}/events?after=${lastSequence.value}`);
    source.onopen=()=>{if(myGeneration===generation){connected.value=true;error.value=null;}};
    for(const type of TYPES) source.addEventListener(type,(raw:MessageEvent)=>{
      if(myGeneration!==generation)return;
      let e:RunEvent;
      try { e=JSON.parse(raw.data) as RunEvent; }
      catch { error.value='Invalid event JSON'; stop(); return; }
      if(e.schemaVersion!=='1.0'||e.runId!==runId||!Number.isSafeInteger(e.sequence)||e.sequence<1||e.type!==type) {
        error.value='Invalid event envelope';stop();return;
      }
      if(e.sequence<=lastSequence.value)return;
      // Event storage uses a contiguous per-run committed sequence. A gap triggers a snapshot recovery.
      if(e.sequence!==lastSequence.value+1){void recover(myGeneration);return;}
      if(type==='access.revoked'){handlers.clearSensitive();lastSequence.value=e.sequence;stop();return;}
      handlers.onEvent(e);lastSequence.value=e.sequence;attempts=0;
      if(['run.completed','run.failed','run.cancelled'].includes(type))stop();
    });
    source.onerror=()=>void recover(myGeneration);
  }
  async function recover(myGeneration:number) {
    if(myGeneration!==generation||recovering)return;
    recovering=true;source?.close();source=null;connected.value=false;
    try {
      const snap=await api.getRun(runId);
      if(myGeneration!==generation)return;
      if(snap.status==='ACCESS_REVOKED'){handlers.clearSensitive();stop();return;}
      // Replace UI from canonical snapshot, then subscribe after it. Do not combine stale partial rows.
      handlers.onSnapshot(snap);lastSequence.value=snap.latestSequence;
      if(TERMINAL.has(snap.status)){stop();return;}
      if(++attempts>6){error.value='重连次数已达上限，请手动重试';stop();return;}
      retry=setTimeout(()=>connect(myGeneration),Math.min(1000*2**(attempts-1),15000));
    } catch(e) {
      if(myGeneration!==generation)return;
      if(e instanceof CommerceApiError && [401,403,404].includes(e.status))handlers.clearSensitive();
      error.value=e instanceof Error?e.message:'运行状态读取失败';stop();
    } finally { if(myGeneration===generation)recovering=false; }
  }
  /** Snapshot-first subscription: no network request during SSR. Call from onMounted or user action. */
  async function start(id:string) {
    stop();runId=id;lastSequence.value=0;attempts=0;error.value=null;
    const g=generation;
    try {
      const snap=await api.getRun(id);if(g!==generation)return;
      if(snap.status==='ACCESS_REVOKED'){handlers.clearSensitive();return;}
      handlers.onSnapshot(snap);lastSequence.value=snap.latestSequence;
      if(!TERMINAL.has(snap.status))connect(g);
    } catch(e) {
      if(g!==generation)return;
      handlers.clearSensitive();error.value=e instanceof Error?e.message:'运行读取失败';
    }
  }
  onBeforeUnmount(stop);
  return {connected,error,lastSequence,start,stop};
}
