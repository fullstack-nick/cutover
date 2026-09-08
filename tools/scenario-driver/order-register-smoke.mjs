import assert from 'node:assert/strict';
import { resolve } from 'node:path';
process.env.CUTOVER_PROFILE='demo';
const { api, token, query, provisionObservers, saveEvidence }=await import('./client.mjs');
const { humanSession }=await import('./human-session.mjs');
const { root, privateDirectory, simulatorRead, until }=await import('../../scripts/lib/local-platform.mjs');
const id=`order-register-${Date.now()}`,directory=resolve(root,'.local/evidence',id),prefix='/api/v1/sites/site-a';
privateDirectory(directory);const evidence={startedAt:new Date().toISOString(),cases:[],orders:[],browserErrors:[]};let session;
try {
  provisionObservers();evidence.worldBefore=await simulatorRead('/sim/v1/equipment');
  const sku=query('core',"SELECT s.sku FROM stock s JOIN products p USING(site_id,sku) WHERE s.site_id='site-a' AND s.on_hand-s.reserved>0 ORDER BY s.sku LIMIT 1;");assert.match(sku,/^SKU-\d{3}$/);
  assert.equal(Number(query('core',"SELECT on_hand-reserved FROM stock WHERE site_id='site-a' AND sku='SKU-099';")),0);
  for(const kind of ['partial','empty']) {
    const body={sourceSystem:'scenario-driver',externalOrderRef:`${id}-${kind}`,storeId:'store-01',priority:5,lines:[...(kind==='partial'?[{sku,quantity:1}]:[]),{sku:'SKU-099',quantity:2}]};
    const response=await api(`${prefix}/orders`,{method:'POST',bearer:await token(),key:body.externalOrderRef,body});assert.equal(response.status,202);
    let order;await until(async()=>{order=(await api(`${prefix}/orders/${response.body.id}`,{bearer:await token()})).body;return order.state===(kind==='partial'?'COMPLETED_WITH_SHORTAGE':'SHORTAGE');},'the register fixture reaches its exact shortage state',90000);
    evidence.orders.push({kind,body,order});
  }
  session=await humanSession('operator-a');const page=session.page;
  page.on('pageerror',()=>evidence.browserErrors.push('An uncaught browser error occurred.'));
  await page.getByRole('button',{name:'Orders',exact:true}).click();
  await page.getByRole('heading',{name:'Order register',exact:true}).waitFor();
  await page.getByRole('button',{name:`${id}-empty`,exact:true}).waitFor();
  await page.getByRole('searchbox',{name:'Order reference',exact:true}).fill(id.toUpperCase());
  await page.getByRole('button',{name:'Search orders',exact:true}).click();
  await page.getByRole('checkbox',{name:'Has unreserved units',exact:true}).check();
  await until(async()=>await page.locator('.order-panel tbody tr').count()===2,'whole-site reference and shortage filter returns both fixtures',15000);
  await page.screenshot({path:resolve(directory,'shortage-register.png'),fullPage:true});
  const partial=evidence.orders.find(item=>item.kind==='partial').order;
  await page.getByRole('button',{name:partial.externalOrderRef,exact:true}).click();
  const dialog=page.getByRole('dialog');await dialog.getByRole('heading',{name:partial.externalOrderRef,exact:true}).waitFor();
  await dialog.locator('.movement-list button').click();await dialog.getByRole('heading',{name:'Verified completion',exact:true}).waitFor();
  await dialog.getByText('execution-service · epoch',{exact:false}).waitFor();
  assert.equal(await dialog.getByRole('button',{name:/cancel/i}).count(),0,'The operator must not receive a supervisor cancellation control.');
  await page.screenshot({path:resolve(directory,'confirmed-shortage-detail.png'),fullPage:true});
  await page.keyboard.press('Escape');await until(async()=>await dialog.count()===1 && !(await dialog.isVisible()),'Escape dismisses the order details',5000);
  assert.equal(await page.getByRole('button',{name:partial.externalOrderRef,exact:true}).evaluate(element=>element===document.activeElement),true,'Closing the native dialog restores the trigger focus.');
  const blocked='**/api/v1/sites/site-a/orders**';await page.route(blocked,route=>route.abort('failed'));
  await page.getByRole('button',{name:'Refresh',exact:true}).click();await page.getByText('Order observations are unavailable.',{exact:true}).waitFor();
  assert.equal(await page.locator('.order-panel tbody tr').count(),2,'Last observed rows remain visible with an explicit stale warning.');
  await page.screenshot({path:resolve(directory,'stale-order-register.png'),fullPage:true});
  await page.unroute(blocked);await until(async()=>!(await page.getByRole('button',{name:'Refresh',exact:true}).isDisabled()),'refresh control is available after the failed observation',10000);
  await page.getByRole('button',{name:'Refresh',exact:true}).click();await page.getByText('Order observations are unavailable.',{exact:true}).waitFor({state:'hidden'});
  await page.getByRole('searchbox',{name:'Order reference',exact:true}).fill('%');await page.getByRole('button',{name:'Search orders',exact:true}).click();await page.getByText('No matching orders',{exact:true}).waitFor();
  await page.getByRole('button',{name:'Clear filters',exact:true}).click();await page.getByRole('button',{name:`${id}-empty`,exact:true}).waitFor();
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:resolve(directory,'mobile-order-register.png'),fullPage:true});
  assert.deepEqual(evidence.browserErrors,[]);
  const movement=partial.movements[0].movementId;
  for(const [owner,table] of [['core','inventory_ledger'],['simulator','execution_ledger']])assert.equal(Number(query(owner,`SELECT count(*) FROM ${table} WHERE movement_id='${movement}'::uuid AND quantity=1;`)),1);
  evidence.worldAfter=await simulatorRead('/sim/v1/equipment');for(const key of ['worldId','journalGeneration'])assert.equal(evidence.worldAfter[key],evidence.worldBefore[key]);
  evidence.cases.push({status:'passed',name:'Deployed order register, whole-site shortages, command proof, operator controls, keyboard focus, retained stale data, empty search and 390-pixel viewport',supports:['A02','A39','A51'],scope:'Order register only; the full task/recovery/migration/audit walkthrough remains a separate A51 gate.'});
  console.log(`${id}: order-register walkthrough passed. ${directory}`);
}catch(error){evidence.failure=error.message;throw error;}
finally{if(session)await session.close();evidence.endedAt=new Date().toISOString();saveEvidence(id,evidence);}
