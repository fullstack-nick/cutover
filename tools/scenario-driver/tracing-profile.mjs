import assert from 'node:assert/strict';
import {target} from '../../scripts/lib/local-platform.mjs';

const services=['equipment-adapter','execution-service','legacy-core','returns-service','shadow-scheduler'];
const allowed=new Set(['OTEL_JAVAAGENT_ENABLED','OTEL_INSTRUMENTATION_JDBC_ENABLED','OTEL_TRACES_SAMPLER','OTEL_TRACES_SAMPLER_ARG','OTEL_BSP_MAX_QUEUE_SIZE','OTEL_BSP_MAX_EXPORT_BATCH_SIZE','OTEL_BSP_EXPORT_TIMEOUT','OTEL_EXPORTER_OTLP_TIMEOUT']);

/** Actual ready pods and an explicit configuration allowlist; never copy credentials or exporter headers. */
export function tracingProfile() {
  const p=target('demo'),profiles=[];
  const pods=JSON.parse(p.kube(['-n','cutover-apps','get','pods','-l','app.kubernetes.io/part-of=cutover','-o','json'])).items;
  for(const pod of pods.filter(pod=>pod.status.phase==='Running'))for(const container of pod.spec.containers){
    if(!services.includes(container.name))continue;
    p.owned(pod);assert.equal(pod.status.containerStatuses?.find(status=>status.name===container.name)?.ready,true);
    const settings={};for(const entry of container.env??[])if(allowed.has(entry.name)){assert.equal(typeof entry.value,'string');settings[entry.name]=entry.value;}
    profiles.push({service:container.name,podUid:pod.metadata.uid,image:container.image,settings});
  }
  profiles.sort((a,b)=>a.service.localeCompare(b.service));assert.deepEqual(profiles.map(profile=>profile.service),services);
  return profiles;
}

export function requireDomainTracing(profiles) {
  for(const profile of profiles)assert.equal(profile.settings.OTEL_INSTRUMENTATION_JDBC_ENABLED,'false',`${profile.service} must use the declared domain/transport tracing profile.`);
}
