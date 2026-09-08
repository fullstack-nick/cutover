import assert from 'node:assert/strict';
import { isIP } from 'node:net';
import { resolve } from 'node:path';
import { root, call, writeJson } from './local-platform.mjs';

export function refreshEquipmentEndpoint(platform){
  assert.equal(platform.profile,'demo');platform.verify();
  const name='cutover-dev-equipment-simulator-1';
  const network=JSON.parse(call('docker',['network','inspect','cutover-kind']))[0];
  assert.equal(network.Labels['dev.cutover.project'],'cutover');
  const box=JSON.parse(call('docker',['inspect',name]))[0];
  assert.equal(box.Config.Labels['dev.cutover.project'],'cutover');assert.equal(box.Config.Labels['com.docker.compose.project'],'cutover-dev');assert.ok(box.State.Running);
  const link=box.NetworkSettings.Networks['cutover-kind'];assert.ok(link,'Deploy the simulator connection to the owned kind network before Start.');
  assert.equal(link.NetworkID,network.Id);assert.equal(isIP(link.IPAddress),4);const address=link.IPAddress;
  const slice=platform.owned(platform.get('endpointslice','equipment-simulator','cutover-platform'));
  assert.equal(slice.metadata.labels['endpointslice.kubernetes.io/managed-by'],'cutover-renderer');assert.equal(slice.metadata.labels['kubernetes.io/service-name'],'equipment-simulator');
  assert.equal(slice.addressType,'IPv4');assert.equal(slice.endpoints.length,1);assert.equal(slice.endpoints[0].addresses.length,1);
  const changes=[];
  for(const [namespace,name,selector,port] of [['cutover-apps','adapter-equipment','equipment-adapter',8443],['cutover-observability','simulator-metrics','prometheus',9091]]){
    const policy=platform.owned(platform.get('networkpolicy',name,namespace));
    assert.deepEqual(policy.spec.podSelector,{matchLabels:{'app.kubernetes.io/name':selector}});
    assert.deepEqual(policy.spec.policyTypes,['Egress']);assert.equal(policy.spec.egress.length,1);
    assert.deepEqual(policy.spec.egress[0].ports,[{protocol:'TCP',port}]);assert.equal(policy.spec.egress[0].to.length,1);
    const previous=policy.spec.egress[0].to[0].ipBlock;assert.deepEqual(Object.keys(previous),['cidr']);assert.match(previous.cidr,/^\d+\.\d+\.\d+\.\d+\/32$/);
    if(previous.cidr!==`${address}/32`){
      const patch=[{op:'test',path:'/metadata/resourceVersion',value:policy.metadata.resourceVersion},{op:'replace',path:'/spec/egress/0/to/0/ipBlock/cidr',value:`${address}/32`}];
      platform.kube(['-n',namespace,'patch','networkpolicy',name,'--type=json','-p',JSON.stringify(patch)]);
      assert.equal(platform.get('networkpolicy',name,namespace).spec.egress[0].to[0].ipBlock.cidr,`${address}/32`);
      changes.push({namespace,name,previous:previous.cidr,current:`${address}/32`});
    }
  }
  const previous=slice.endpoints[0].addresses[0];
  if(previous!==address){
    const patch=[{op:'test',path:'/metadata/resourceVersion',value:slice.metadata.resourceVersion},{op:'replace',path:'/endpoints/0/addresses/0',value:address}];
    platform.kube(['-n','cutover-platform','patch','endpointslice','equipment-simulator','--type=json','-p',JSON.stringify(patch)]);
  }
  assert.equal(platform.get('endpointslice','equipment-simulator','cutover-platform').endpoints[0].addresses[0],address);
  const observed={at:new Date().toISOString(),simulatorContainerId:box.Id,previous,address,policyChanges:changes};
  writeJson(resolve(root,'.local/operations/equipment-endpoint.json'),observed);return observed;
}
