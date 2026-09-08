import {resolve} from 'node:path';

/** Capture populated, current views through the actual reviewer navigation. */
export async function captureReviewerConsole(page,directory){
  await page.setViewportSize({width:1440,height:1000});
  for(const view of ['Overview','Orders','Returns','Tasks','Migrations']){
    await page.getByRole('button',{name:view,exact:true}).click();
    await page.getByRole('heading',{name:view==='Overview'?'The floor, in focus.':view,exact:true}).waitFor();
    if(view==='Tasks')await page.getByRole('checkbox',{name:'Show active tasks',exact:true}).uncheck();
    if(['Overview','Orders','Returns','Tasks'].includes(view))await page.locator('tbody tr').first().waitFor();
    if(view==='Migrations')await page.locator('.route-owner').first().waitFor();
    await page.evaluate(()=>new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(done))));
    await page.screenshot({path:resolve(directory,`${view.toLowerCase()}.png`)});
  }
}
