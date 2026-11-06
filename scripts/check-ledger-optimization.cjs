'use strict';
// Local browser fixtures only. No Android data or external services are used.
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 try{
  for(const reducedMotion of ['no-preference','reduce']){
   const page=await browser.newPage({viewport:{width:393,height:852},reducedMotion}),errors=[];
   page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',r=>r.abort());
   await page.addInitScript(()=>{
    const at=Date.now();window.calls={snapshot:0,page:0,details:0};window.fixtureRevision='1';window.fixtureTick=0;
    window.fixtureRows=Array.from({length:450},(_,i)=>({id:450-i,uid:'fixture-'+i,merchant:'合成商户'+i,category:'餐饮',amountCents:1500,direction:'EXPENSE',occurredAt:at,state:'POSTED',source:'MANUAL',note:'',historyRevision:'0',hasScreenshot:false}));
    const rows=(month,query,direction)=>fixtureRows.filter(r=>r.direction===direction&&(!query||r.merchant.includes(query)));
    const ledgerView=(items,remaining)=>({hasMore:remaining,summary:{totals:{expense:675000,income:0,expenseCount:450,incomeCount:0},previous:{},categories:[],days:[]},nextCursor:remaining?{occurredAt:items.at(-1).occurredAt,id:items.at(-1).id}:null});
    const base=()=>({ok:true,channels:{ocr:{enabled:true,permitted:true}},accounts:[],bankOptions:[],recognitionTasks:[],automatic:{enabled:false,applications:[]},captureHealth:{ready:true,shakeReady:true,shakeStatus:'ready',shakeMessage:'摇一摇运行中',lastSuccessfulAt:at},automationRunning:true});
    window.LedgerNative={
     snapshotFirst(month,query,direction,known){calls.snapshot++;const key=JSON.stringify([month,query,direction,fixtureRevision,fixtureTick]);if(known===key)return JSON.stringify({ok:true,unchanged:true});const all=rows(month,query,direction),head=all.slice(0,200);return JSON.stringify({...base(),records:head,viewKey:key,dataRevision:fixtureRevision,ledgerView:ledgerView(head,all.length>200)})},
     recordPage(month,query,direction,beforeAt,beforeId,revision){calls.page++;if(revision!==fixtureRevision)return JSON.stringify({ok:true,reset:true});const all=rows(month,query,direction).filter(r=>r.occurredAt<beforeAt||r.occurredAt===beforeAt&&r.id<beforeId),next=all.slice(0,200);return JSON.stringify({ok:true,records:next,dataRevision:fixtureRevision,ledgerView:ledgerView(next,all.length>200)})},
     recordDetails(id){calls.details++;return JSON.stringify({ok:true,record:fixtureRows.find(r=>r.id===id),rawText:'',history:[]})},remember(){},
     save(json){const values=JSON.parse(json),row=fixtureRows.find(r=>r.id===values.id);Object.assign(row,values,{amountCents:Math.round(Number(values.amount)*100)});fixtureRevision=String(Number(fixtureRevision)+1);return JSON.stringify({ok:true})},
    };
   });
   const webRoot=process.env.LEDGER_WEB_ROOT||path.resolve(__dirname,'../app/src/main/assets/web');
   await page.goto(pathToFileURL(path.join(webRoot,'index.html')).href);
   assert.equal(await page.locator('[data-record]').count(),200,`Initial list failed: ${JSON.stringify(errors)}`);
   const refresh=await page.evaluate(()=>{let renders=0;const original=render;render=()=>{renders++;return original()};const start=performance.now();for(let i=0;i<20;i++)window.LedgerUI.event('refresh');render=original;return {renders,ms:performance.now()-start}});
   assert.equal(refresh.renders,0,'An unchanged native snapshot must not rebuild the list');
   await page.locator('#load-more-records').click();assert.equal(await page.locator('[data-record]').count(),400);
   await page.locator('#load-more-records').click();assert.equal(await page.locator('[data-record]').count(),450);
   assert.equal(await page.evaluate(()=>new Set(records.map(r=>r.id)).size),450,'Equal timestamps must not repeat rows');
   assert.equal(await page.evaluate(()=>calls.snapshot),21,'Appending must not reload earlier pages');
   await page.evaluate(()=>{fixtureTick++;window.LedgerUI.event('refresh')});
   assert.equal(await page.locator('[data-record]').count(),450,'A status-only snapshot keeps loaded history');
   await page.evaluate(()=>{viewKey='';fixtureRevision='2';refresh()});
   assert.equal(await page.locator('[data-record]').count(),200);
   await page.evaluate(()=>fixtureRevision='3');await page.locator('#load-more-records').click();
   assert.equal(await page.locator('[data-record]').count(),200,'A changed revision restarts pagination');
   const before=await page.evaluate(()=>calls.snapshot);
   await page.locator('[data-record="450"]').click();
   await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
   assert.equal(await page.evaluate(()=>calls.snapshot),before,'Opening a same-month detail must not reload the list');
   assert.equal(await page.evaluate(()=>calls.details),1);
   assert.equal(await page.locator('#record-panel').getAttribute('aria-modal'),'true');
   for(let i=0;i<15;i++){await page.keyboard.press('Tab');assert(await page.evaluate(()=>document.querySelector('#record-panel').contains(document.activeElement)),'Focus must stay inside the record panel')}
   await page.locator('#note').fill('修改但尚未保存');
   const restoredState=await page.evaluate(()=>JSON.parse(JSON.stringify(getState())));
   await page.evaluate(()=>cancelEditor(true));await page.waitForFunction(()=>document.querySelector('#record-panel').hidden);
   await page.evaluate(state=>restoreState(state),restoredState);
   await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
   assert.equal(await page.evaluate(()=>editorDirty()),true,'Restoring activity state must retain the unsaved baseline');
   await page.locator('#record-panel .back').click();
   assert.equal(await page.locator('#sheet-title').textContent(),'保留这次修改？');
   await page.locator('#keep-editing').click();await page.waitForFunction(()=>!document.querySelector('#sheet').open);
   assert.equal(await page.locator('#note').inputValue(),'修改但尚未保存');
   await page.keyboard.press('Escape');await page.locator('#discard-edits').click();
   await page.waitForFunction(()=>document.querySelector('#record-panel').hidden);
   assert(await page.evaluate(()=>document.activeElement.dataset.record==='450'&&!document.querySelector('.workspace').inert));
   // Visible compact shapes keep larger invisible hit areas.
   const hit=await page.evaluate(()=>{const e=document.querySelector('[data-filter="expense"]'),r=e.getBoundingClientRect();return document.elementFromPoint(r.x+r.width/2,r.y-4)?.closest('button')===e});
   assert(hit,'The compact filter must accept taps beyond its visible 30px capsule');
   await page.locator('[data-record="450"]').click();
   await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
   await page.locator('#note').fill('已保存的修改');
   await page.evaluate(()=>submitDraft());
   await page.waitForFunction(()=>document.querySelector('#record-panel').hidden);
   assert.equal(await page.evaluate(()=>fixtureRows.find(r=>r.id===450).note),'已保存的修改');
   assert.equal(await page.locator('#sheet').evaluate(e=>e.open),false,'A successful save must close without a discard prompt');
   for(const viewport of [{width:320,height:740},{width:740,height:393}]){
    await page.setViewportSize(viewport);await page.evaluate(()=>{go('settings');drawSettings()});
    assert.equal(await page.locator('#settings .steps .step').count(),3,'The restored settings page keeps the original three-step guide');
    assert(await page.locator('#settings .steps').isVisible());
    assert.match(await page.locator('[data-channel-info="ocr"]').textContent(),/运行中/);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Configured settings must not overflow horizontally');
    await page.evaluate(()=>window.LedgerUI.event('fontScale',1.5));
    const overflow=await page.locator('[data-channel-info="ocr"]').evaluate(e=>e.scrollWidth>e.clientWidth);
    assert.equal(overflow,false,'Configured status must wrap with large text');
    if(process.env.OPTIMIZATION_SCREENSHOTS){fs.mkdirSync(process.env.OPTIMIZATION_SCREENSHOTS,{recursive:true});await page.screenshot({path:path.join(process.env.OPTIMIZATION_SCREENSHOTS,`settings-${viewport.width}-${reducedMotion}.png`),fullPage:true})}
   }
   assert.deepEqual(errors,[]);console.log(`PASS ${reducedMotion}: unchanged refresh (20 calls ${refresh.ms.toFixed(1)}ms), cursor pages, revision reset, one detail read, focus containment, unsaved fields and filter hit area`);
   await page.close();
  }
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
