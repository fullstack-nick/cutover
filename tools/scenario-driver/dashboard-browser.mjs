import assert from 'node:assert/strict';
import { credentials } from './client.mjs';

/** Verify rendered local panels, not just dashboard headings or successful datasource requests. */
export async function verifyDashboard(page,screenshotPath) {
  const url='http://127.0.0.1:8783/d/cutover-platform/cutover-local-operations';
  await page.setViewportSize({width:1440,height:1000});
  await page.goto(url);
  try {
    await page.getByRole('textbox',{name:'Email or username',exact:true}).fill('cutover-admin');
    await page.getByRole('textbox',{name:'Password',exact:true}).fill(credentials.passwords.grafana_admin);
    await page.getByRole('button',{name:'Log in',exact:true}).click();
  } catch { throw new Error('The prepared local dashboard login did not complete.'); }
  await page.waitForURL(current=>!current.pathname.startsWith('/login'));
  await page.goto(url);
  const targets=page.getByTestId('stat-panel-1'),memory=page.getByTestId('timeseries-panel-2'),http=page.getByTestId('timeseries-panel-3');
  await targets.getByText('6',{exact:true}).waitFor({timeout:60000});
  const memoryServices=['equipment-adapter','equipment-simulator','execution-service','legacy-core','returns-service','shadow-scheduler'];
  const httpServices=['equipment-adapter','legacy-core','returns-service'];
  for(const service of memoryServices)await memory.getByTestId(`data-testid VizLegend series ${service}`).waitFor();
  for(const service of httpServices)await http.getByTestId(`data-testid VizLegend series ${service}`).waitFor();
  for(const panel of [targets,memory,http]){
    assert.ok(await panel.locator('canvas').count()>0,'Every observed panel must have its rendered plot.');
    assert.ok(!/No data|Error loading|Query error/i.test(await panel.innerText()));
  }
  await page.evaluate(()=>new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(done))));
  await page.screenshot({path:screenshotPath,fullPage:true});
  return {applicationTargets:6,memoryServices,httpServices,renderedPanels:3};
}
