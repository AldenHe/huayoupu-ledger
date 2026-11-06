/* Browser-only synthetic data; checks fixed tabs while entering scrolled screens. */
'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 try {
  for(const reducedMotion of ['no-preference','reduce'])for(const width of [320,393,740]){
   const page=await browser.newPage({viewport:{width,height:852},reducedMotion});
   const errors=[];page.on('pageerror',e=>errors.push(e.message));
   await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/web/index.html')).href);
   await page.evaluate(()=>{document.body.classList.add('native-app');records=Array.from({length:30},(_,i)=>({id:900000+i,state:'POSTED',source:'MANUAL',amountCents:100,occurredAt:Date.now(),direction:'EXPENSE',merchant:'合成商户',category:'分类'+i}));render();go('stats')});
   await page.waitForTimeout(200);
   await page.evaluate(()=>document.querySelector('#stats').scrollTop=900);
   await page.getByRole('button',{name:'首页',exact:true}).filter({visible:true}).click();
   await page.getByRole('button',{name:'统计',exact:true}).filter({visible:true}).click();
   // Observe several actual animation frames, not only the final settled layout.
   const samples=await page.evaluate(()=>new Promise(resolve=>{const values=[];function sample(){const e=document.querySelector('#stats'),r=e.querySelector('.bottom-nav').getBoundingClientRect();values.push({scroll:e.scrollTop,bottom:r.bottom,height:innerHeight});if(values.length<8)requestAnimationFrame(sample);else resolve(values)}requestAnimationFrame(sample)}));
   samples.forEach(s=>{assert(s.scroll>100);assert(Math.abs(s.bottom-s.height)<2,JSON.stringify({width,reducedMotion,...s}))});
   await page.locator('#stats-type [data-type="income"]').click();
   assert.equal(await page.locator('#analysis-total').textContent(),'¥ 0');
   assert.equal(await page.locator('#stats-type [data-type="income"]').getAttribute('aria-pressed'),'true');
   assert(await page.evaluate(()=>Math.abs(document.querySelector('#stats .bottom-nav').getBoundingClientRect().bottom-innerHeight)<2));
   await page.locator('#stats-type [data-type="expense"]').click();
   assert.equal(await page.locator('#analysis-total').textContent(),'¥ 30');
   await page.getByRole('button',{name:'资产',exact:true}).filter({visible:true}).click();
   await page.getByRole('button',{name:'添加账户',exact:true}).click();
   await page.locator('#account-form').waitFor({state:'visible'});
   assert.equal(await page.locator('#account-name').count(),1);
   assert.deepEqual(errors,[]);
   console.log(`PASS width=${width} motion=${reducedMotion}: scrolled tabs stay fixed; asset plus opens editor`);
   await page.close();
  }
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
