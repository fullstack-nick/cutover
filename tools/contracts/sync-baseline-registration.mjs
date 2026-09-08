import {readFileSync,writeFileSync} from 'node:fs';
const base=new URL('../../contracts/',import.meta.url);
const read=path=>JSON.parse(readFileSync(new URL(path,base),'utf8'));
const write=(path,data)=>writeFileSync(new URL(path,base),JSON.stringify(data,null,2)+'\n');
const object=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:true});
const id={type:'string',format:'uuid'},number={type:'integer',minimum:0},site={type:'string',pattern:'^[a-z][a-z0-9-]{0,39}$'};
const movement=read('schemas/movement.v1.json');delete movement.$schema;
const request=object({registrationId:id,expectedControlVersion:number,items:{type:'array',maxItems:32,items:object({taskId:id,siteId:site,movementId:id,state:{enum:['COMPLETED','CANCELLED']},allocationId:{...id,type:['string','null']},epoch:{...number,type:['integer','null']},movement})}});
write('schemas/baseline-registration-request.v1.json',{$schema:'https://json-schema.org/draft/2020-12/schema',...request});
const internal=read('openapi/internal.v1.json');
internal.paths['/internal/v1/sites/{site}/legacy-registrations']={
 parameters:[{name:'site',in:'path',required:true,schema:site}],
 post:{operationId:'registerOriginalLegacyInventory',description:'Initial owner maintenance only. The legacy-core client supplies up to 32 settled original tasks while adapter dispatch is paused at the expected control version. Retained allocations, physical completion or cancellation states must agree. This creates registration receipts without changing ownership or creating physical work.',requestBody:{required:true,content:{'application/json':{schema:{$ref:'../schemas/baseline-registration-request.v1.json'}}}},responses:{'200':{description:'Complete batch of retained allocation receipts and gate version'},default:{$ref:'#/components/responses/problem'}}}
};
write('openapi/internal.v1.json',internal);
