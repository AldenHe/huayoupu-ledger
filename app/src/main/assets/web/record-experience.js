/* Shared selected-account identity for manual entry and editable bill details. */
const RecordExperience=(()=>{
 let manual={accountUid:null,accountSpecified:false,occurredAt:Date.now()},ready=false,placements=[],supplementHome=null;
 function move(node,target){let place=placements.find(p=>p.node===node);if(!place){const home=document.createComment('record-field-home');node.before(home);place={node,home,target};placements.push(place)}target.append(node)}
 const icon=name=>`<i class="fa-solid fa-${name}" aria-hidden="true"></i>`;
 const paths={card:'M3 8h18M3 12h18M6 17h4M14 17h2',bolt:'m13 2-8 12h6l-1 8 9-13h-7z',clock:'M12 7v5l4 3',receipt:'M7 8h10M7 12h10M8 16h8',bubble:'M7 8h10M7 12h7',back:'m12 5-7 7 7 7M5 12h15',close:'m5 5 14 14M19 5 5 19',down:'m6 9 6 6 6-6',right:'m9 5 7 7-7 7',paperclip:'m9 12 6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l9-9'};
 function line(name){const frame={card:'<rect x="2" y="4" width="20" height="16" rx="2"/>',clock:'<circle cx="12" cy="12" r="10"/>',receipt:'<path d="M4 3h16v19l-4-2-4 2-4-2-4 2z"/>',bubble:'<path d="M4 3h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-9l-5 4v-4H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/>'}[name]||'';return `<svg class="record-line-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${frame}<path d="${paths[name]||paths.bolt}"/></svg>`}
 function mirror(id){const input=$(id);let shell=input.closest('.record-value-shell');if(!shell){shell=document.createElement('span');shell.className='record-value-shell record-'+id+'-value';input.before(shell);const value=document.createElement('span');value.className='record-value';value.setAttribute('aria-hidden','true');shell.append(value,input);input.addEventListener('input',syncMirrors)}syncMirrors()}
 function syncMirrors(){['merchant','amount'].forEach(id=>{const input=$(id),shell=input.closest('.record-value-shell');if(!shell)return;const value=shell.querySelector('.record-value');value.textContent=id==='amount'&&/^\d+(\.\d{1,2})?$/.test(input.value)?amountFormatter.format(Number(input.value)):input.value||input.placeholder});requestAnimationFrame(()=>{const field=document.querySelector('.reference-record .amount-field');if(!field)return;field.style.removeProperty('--record-amount-size');const available=field.parentElement.clientWidth-2,first=field.firstElementChild.getBoundingClientRect(),last=field.lastElementChild.getBoundingClientRect(),actual=last.right-first.left;if(actual>available)field.style.setProperty('--record-amount-size',Math.max(24,46*available/actual)+'px');const card=field.closest('.confirm-card'),badge=card.querySelector('.pending'),merchant=card.querySelector('.merchant-row');merchant.style.maxWidth=Math.max(64,card.clientWidth-2*(badge.offsetWidth+12)-37)+'px'})}
 function restoreSupplement(){if(!supplementHome)return;const node=$('record-supplement');supplementHome.replaceWith(node);supplementHome=null;node.open=false;node.hidden=true}
 function showSupplement(){closeSheet(false);showSheet('标签与更多信息','<div id="record-more"></div>');const node=$('record-supplement');supplementHome=document.createComment('record-supplement-home');node.before(supplementHome);$('record-more').append(node);node.hidden=false;node.open=true}
 function defaultAccount(){return accounts.find(a=>a.isDefault)}
 function accountLabel(uid){return accounts.find(a=>a.uid===uid)?.name||'未关联账户'}
 function manualFields(reset=false){
  if(reset)manual={accountUid:defaultAccount()?.uid||null,accountSpecified:true,occurredAt:Date.now()};
  if(!$('manual-account'))return;
  const a=accounts.find(a=>a.uid===manual.accountUid);
  $('manual-account').innerHTML=(a?AccountExperience.symbol(a):icon('wallet'))+`<span>${manualType==='income'?'存入账户':'支出账户'}：${escapeText(accountLabel(manual.accountUid))}</span>`+icon('chevron-down');
  $('manual-date-label').textContent=localTime(manual.occurredAt).slice(0,10)===localTime(Date.now()).slice(0,10)?'今天':localTime(manual.occurredAt).slice(5,10).replace('-','月')+'日';
  $('manual-time').value=localTime(manual.occurredAt);
  $('manual').classList.toggle('income-entry',manualType==='income');
  document.querySelector('#manual .entry-import').hidden=manualType==='income';
  document.querySelector('#manual .entry-save').textContent='保存入账';
 }
 function setManualAccount(uid){manual.accountUid=uid;manual.accountSpecified=true;manualFields();remember()}
 function manualValues(){return {accountUid:manual.accountUid,occurredAt:manual.occurredAt}}
 function restore(value){if(!value)return;manual={accountUid:value.accountUid??null,accountSpecified:true,occurredAt:Number.isFinite(value.occurredAt)?value.occurredAt:Date.now()};manualFields()}
 function init(){
  if(ready)return;ready=true;
  const fields=document.createElement('div');fields.className='manual-account-time';fields.innerHTML=`<button id="manual-account" type="button"></button><label class="manual-date" for="manual-time">${icon('calendar-days')}<span id="manual-date-label">今天</span>${icon('chevron-down')}<input id="manual-time" type="datetime-local" aria-label="收入或支出交易时间"/></label>`;
  document.querySelector('#manual .entry-import').before(fields);
  $('manual-account').onclick=()=>AccountExperience.choose(manualType==='income'?'选择存入账户':'选择支出账户',manual.accountUid,uid=>setManualAccount(uid));
  $('manual-time').onchange=()=>{const time=new Date($('manual-time').value).getTime();if(Number.isFinite(time)){manual.occurredAt=time;manualFields();remember()}};
  document.querySelectorAll('#manual-type button').forEach(b=>b.addEventListener('click',()=>manualFields()));
  manualFields(true);
 }
 function render(){
  init();if(!draft)return;const editor=$('import'),active=!!draft.id;
  editor.classList.toggle('reference-record',active);editor.classList.toggle('income-record',active&&$('direction').dataset.value==='INCOME');$('record-panel').classList.toggle('reference-record-panel',active);$('record-shade').classList.toggle('reference-record-shade',active);
  if(!active){restoreSupplement();placements.forEach(p=>p.home.after(p.node));editor.querySelectorAll('.record-value-shell').forEach(shell=>shell.replaceWith(shell.querySelector('input')));const back=editor.querySelector('.page-title .back');if(back._recordOriginalHTML!=null)back.innerHTML=back._recordOriginalHTML;const note=editor.querySelector('.note-row');note.querySelector('svg')?.remove();if(note._recordOriginalIcon&&!note.querySelector('i'))note.insertAdjacentHTML('afterbegin',note._recordOriginalIcon);const reimbursement=editor.querySelector('#bill-reimbursement-editor>span');if(reimbursement._recordOriginalHTML!=null)reimbursement.innerHTML=reimbursement._recordOriginalHTML;editor.querySelector('.merchant-row').style.removeProperty('max-width');$('note').placeholder='添加备注（可选）';['record-fields','record-attachment','record-supplement','record-close'].forEach(id=>{if($(id))$(id).hidden=true});return}
  placements.forEach(p=>p.target.append(p.node));['record-fields','record-attachment','record-close'].forEach(id=>{if($(id))$(id).hidden=false});
  if(!$('record-close')){const close=document.createElement('button');close.type='button';close.id='record-close';close.setAttribute('aria-label','关闭账单详情');close.innerHTML=line('close');close.onclick=()=>cancelEditor();editor.querySelector('.page-title').append(close)}
  const back=editor.querySelector('.page-title .back');if(back._recordOriginalHTML==null)back._recordOriginalHTML=back.innerHTML;back.innerHTML=line('back');
  if(!$('record-fields')){
   const fields=document.createElement('section');fields.id='record-fields';fields.className='reference-record-fields';
   const account=document.createElement('div');account.className='reference-field record-account-field';account.innerHTML=`<span>${line('card')}<span id="record-account-label">支出账户</span></span><button type="button" id="record-account"></button>`;
   const category=document.createElement('div');category.className='reference-field';category.innerHTML=`<span>${line('bolt')}分类</span>`;move($('category'),category);
   const time=document.createElement('div');time.className='reference-field';time.innerHTML=`<span>${line('clock')}交易时间</span>`;move($('time-label'),time);move($('occurred-at'),time);
   const note=document.querySelector('#import .note-row');
   const reimbursement=$('bill-reimbursement-editor');
   fields.append(account,category,time);move(reimbursement,fields);move(note,fields);
   document.querySelector('#import .confirm-card').after(fields);
   $('record-account').onclick=()=>AccountExperience.choose($('direction').dataset.value==='INCOME'?'选择存入账户':'选择支出账户',draft.accountUid,uid=>{draft.accountUid=uid;draft.accountSelectionSpecified=true;render();remember()});
   const toggle=document.createElement('button');toggle.id='record-reimbursement-switch';toggle.type='button';toggle.className='reference-switch';toggle.setAttribute('role','switch');toggle.setAttribute('aria-label','标记为待报销');toggle.onclick=()=>{const yes=$('reimbursement-yes').getAttribute('aria-pressed')!=='true';document.querySelectorAll('#bill-reimbursement-editor [data-reimbursement]').forEach(b=>b.setAttribute('aria-pressed',String((b.dataset.reimbursement==='true')===yes)));render();remember()};reimbursement.append(toggle);
   const advanced=document.createElement('details');advanced.id='record-supplement';advanced.hidden=true;advanced.innerHTML='<summary>标签与更多信息</summary>';
   [$('bill-labels-editor'),$('review-reason'),$('refund-match'),$('original-message'),editor.querySelector('.editor-tools')].forEach(node=>move(node,advanced));
   fields.after(advanced);
   const attachment=document.createElement('section');attachment.id='record-attachment';attachment.className='record-attachment';attachment.innerHTML=`<button id="record-original" type="button">${line('paperclip')}<span>原始付款截图</span>${line('right')}</button><div id="record-originals" hidden></div>`;
   advanced.after(attachment);move($('capture-gallery'),$('record-originals'));move(editor.querySelector('.scanner'),$('record-originals'));
   $('record-original').onclick=()=>{const first=(draft.history||[]).find(h=>h.hasScreenshot)||(draft.history||[])[0];if(first){viewHistory(first.sequence);return}if(previewUrl){viewOrPickImage();return}const originals=$('record-originals');originals.hidden=!originals.hidden;$('record-original').setAttribute('aria-expanded',String(!originals.hidden))};
  }
  const a=accounts.find(a=>a.uid===draft.accountUid);
  const income=$('direction').dataset.value==='INCOME',isDefault=a&&(a.isDefault||a.isWechatDefault&&draft.paymentProvider==='wechat'||a.isAlipayDefault&&draft.paymentProvider==='alipay');
  $('record-attachment').hidden=income;
  const symbol=a?.kind==='wechat'?'<i class="fa-brands fa-weixin record-wechat" aria-hidden="true"></i>':a?AccountExperience.symbol(a):line('card');
  $('record-account').innerHTML='<span class="record-select-surface">'+symbol+`<span class="record-select-value">${escapeText(accountLabel(draft.accountUid))}${isDefault?' (默认)':''}</span>`+line('down')+'</span>';$('record-account').disabled=working;
  const categoryValue=$('category').textContent,categoryIcon=iconForCategory(categoryValue);$('category').innerHTML='<span class="record-select-surface">'+(categoryIcon?icon(categoryIcon):line('bolt'))+`<span class="record-select-value">${escapeText(categoryValue)}</span>`+line('down')+'</span>';
  $('record-account-label').textContent=income?'存入账户':'支出账户';
  $('record-reimbursement-switch').setAttribute('aria-checked',$('reimbursement-yes').getAttribute('aria-pressed'));$('record-reimbursement-switch').disabled=working;
  const reimbursementLabel=document.querySelector('#bill-reimbursement-editor>span');if(reimbursementLabel._recordOriginalHTML==null)reimbursementLabel._recordOriginalHTML=reimbursementLabel.innerHTML;reimbursementLabel.innerHTML=line('receipt')+'标记为待报销';
  const note=editor.querySelector('.note-row');if(!note.querySelector('svg')){note._recordOriginalIcon=note.querySelector('i')?.outerHTML;note.querySelector('i')?.remove();note.insertAdjacentHTML('afterbegin',line('bubble'))}$('note').placeholder='添加备注信息...';
  $('record-original').querySelector('span').textContent=(draft.history||[]).some(h=>h.hasScreenshot)||previewUrl?'原始付款截图（已智能识别）· 点击查看大图':'原始付款截图未保存';
  editor.querySelector('.card-heading .pending').textContent=draft.state==='POSTED'?(draft.source==='MANUAL'?'已入账':'已自动入账 ✓'):'待确认';
  mirror('merchant');mirror('amount');updateAmountSign();
 }
 return {init,render,manualFields,manualValues,setManualAccount,state:()=>({...manual}),restore,showSupplement,restoreSupplement};
})();
