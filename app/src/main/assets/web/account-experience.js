/* Account presentation. Native storage owns every balance, preference and transfer. */
const AccountExperience=(()=>{
 let detail=null;
 const icon=(name)=>`<i class="fa-solid fa-${name}" aria-hidden="true"></i>`;
 const parse=value=>{const result=JSON.parse(value);if(!result.ok)throw Error(result.error||'操作失败，请重试');return result};
 const find=uid=>accounts.find(a=>a.uid===uid);
 const symbol=a=>`<span class="account-app-symbol">${accountSymbol(a?.kind||'custom')}</span>`;
 const fmt=cents=>assetsHidden?'****':cents<0?signedMoney(Math.abs(cents)):'¥ '+money(cents);
 const preferred=a=>!!(a.isDefault||a.isWechatDefault||a.isAlipayDefault);
 const orderedAccounts=()=>[...accounts].sort((a,b)=>Number(preferred(b))-Number(preferred(a)));

 function assets(){
  const included=accounts.filter(a=>a.includedInAssets!==false),total=type=>included.filter(a=>a.type===type).reduce((s,a)=>s+a.amountCents,0);
  $('net-amount').innerHTML=assetsHidden?'****':`${total('ASSET')<total('LIABILITY')?'− ':''}<small>¥</small>${money(Math.abs(total('ASSET')-total('LIABILITY')))}`;
  $('asset-summary').innerHTML=`<div>总资产<b>${fmt(total('ASSET'))}</b></div><div>总负债<b>${fmt(total('LIABILITY'))}</b></div>`;
  $('account-count').textContent=`共 ${accounts.length} 个账户`;
  $('account-preferences').innerHTML='<h2 class="sr-only" id="account-preferences-title">默认记账扣款偏好</h2><div class="preference-controls" role="group" aria-labelledby="account-preferences-title">'+[['common','通用','wallet','isDefault'],['wechat','微信','','isWechatDefault'],['alipay','支付宝','','isAlipayDefault']].map(([channel,label,glyph,flag])=>{
   const account=accounts.find(a=>a[flag]);return `<button type="button" data-preference="${channel}" aria-label="${label}默认扣款，当前${escapeText(account?.name||(channel==='common'?'未设置':'跟随通用'))}，点击设置"><span class="preference-surface">${channel==='common'?icon(glyph):symbol({kind:channel})}<span>默认扣款</span>${icon('chevron-down')}</span></button>`;
  }).join('')+'</div>';
  $('account-preferences').querySelectorAll('[data-preference]').forEach(b=>b.onclick=()=>choosePreference(b.dataset.preference));
  $('accounts').innerHTML=orderedAccounts().map(a=>`<button type="button" class="reference-account-row" data-account="${a.id}" aria-label="查看账户 ${escapeText(a.name)}">${symbol(a)}<span class="account-identity"><b>${escapeText(a.name)}</b>${accountDefaultRoles(a).length?`<small>${escapeText(accountDefaultRoles(a).join(' · '))}</small>`:a.type==='LIABILITY'?'<small>信用账户 · 负债</small>':a.includedInAssets===false?'<small>不计入总资产</small>':''}</span><strong>${fmt(a.amountCents)}</strong>${icon('chevron-right')}</button>`).join('')||'<div class="empty-state">还没有账户<br>添加常用账户，让收支各有所属</div>';
  $('accounts').querySelectorAll('[data-account]').forEach(b=>b.onclick=()=>open(Number(b.dataset.account)));
  $('hide-assets').setAttribute('aria-label',assetsHidden?'显示资产':'隐藏资产');$('hide-assets').setAttribute('aria-pressed',String(assetsHidden));$('hide-assets').innerHTML=icon(assetsHidden?'eye-slash':'eye');
  if(detail&&page==='account-detail')renderDetail(false);
 }
 function choose(title,current,onChoose,allowNone=true,noneLabel='不关联账户'){
  showSheet(title,`${allowNone?`<button class="account-choice" data-account-uid="">${icon('ban')}<span>${noneLabel}</span>${!current?icon('check'):''}</button>`:''}<div class="account-picker">${orderedAccounts().map(a=>`<button class="account-choice ${a.uid===current?'selected':''}" data-account-uid="${a.uid}" aria-label="${escapeText(a.name)}，余额${money(a.amountCents)}">${symbol(a)}<span><b>${escapeText(a.name)}</b><small>${fmt(a.amountCents)}</small></span>${a.uid===current?icon('check'):''}</button>`).join('')||'<p class="sheet-copy">请先在资产页添加账户。</p>'}</div>`);
  $('sheet').classList.add('account-picker-sheet');
  UiScroll.mount(document.querySelector('.account-picker'),5);
  $('sheet-content').querySelectorAll('[data-account-uid]').forEach(b=>b.onclick=()=>{try{onChoose(b.dataset.accountUid||null);closeSheet();}catch(error){notify(error.message)}});
 }
 function choosePreference(channel){
  const key={common:'isDefault',wechat:'isWechatDefault',alipay:'isAlipayDefault'}[channel];
  choose(`${{common:'通用',wechat:'微信',alipay:'支付宝'}[channel]}默认账户`,accounts.find(a=>a[key])?.uid,uid=>{
   if(!native)throw Error('请在 Android 应用中设置账户');parse(native.setAccountPreference(channel,uid||''));refresh();assets();
  },channel!=='common','跟随通用默认');
 }
 function edit(id=0){
  const a=accounts.find(a=>a.id===id)||{id:0,name:'',description:'',amountCents:0,type:'ASSET',kind:'alipay',includedInAssets:true};
  const nameLimit=20;
  const bank=!!accountBank(a.kind)||a.kind==='bank';
  showSheet(id?'编辑账户':'添加新账户',`<form id="account-form" class="reference-account-form" novalidate>
   <div class="account-type-grid" role="group" aria-label="账户类别">${[['wechat','微信'],['alipay','支付宝'],['bank','银行卡'],['cash','现金']].map(([kind,label])=>`<button type="button" data-account-kind="${kind}" class="${(bank?'bank':a.kind)===kind?'selected':''}">${symbol({kind})}<span>${label}</span></button>`).join('')}</div>
   <input id="account-kind" type="hidden" value="${bank?'bank':escapeText(a.kind)}"/>
   <div id="account-bank-field" class="account-bank-fields" ${bank?'':'hidden'}><label>选择银行<select id="account-bank">${bankOptions.map(b=>`<option value="${escapeText(b.kind)}">${escapeText(b.label)}</option>`).join('')}<option value="bank">其他银行</option></select></label><label>银行卡类型<select id="account-type"><option value="ASSET">储蓄卡</option><option value="LIABILITY">信用卡</option></select></label></div>
   <label class="account-name-field" for="account-name"><span>账户名称（如：工商银行储蓄卡）</span><input id="account-name" maxlength="40" value="${escapeText(a.name||(id?'':'支付宝'))}" placeholder="账户名称" aria-describedby="account-name-count account-name-error"/><span id="account-name-count" class="account-name-count" aria-live="polite"></span></label>
   <p id="account-name-error" role="alert" class="account-field-error" hidden></p>
   <label class="account-balance-field" for="account-amount"><span>¥</span><input id="account-amount" aria-label="当前账户余额" inputmode="decimal" type="text" maxlength="15" value="${id?a.amountCents/100:'0'}" placeholder="0" aria-describedby="account-amount-error"/></label>
   <p id="account-amount-error" role="alert" class="account-field-error" hidden></p>
   <details class="account-advanced"><summary>高级选项（计入资产、备注） ${icon('chevron-down')}</summary><label class="account-include"><span>计入总资产</span><input id="account-included" type="checkbox" ${a.includedInAssets!==false?'checked':''}/></label><label for="account-description">备注<input id="account-description" maxlength="80" value="${escapeText(a.description)}" placeholder="可选"/></label>${!bank&&a.type==='LIABILITY'?'<p class="sheet-copy">此账户保留负债类型。</p>':''}</details>
   <button class="reference-primary" id="save-account" type="submit">${id?'保存账户':'保存并创建账户'}</button>
  </form>`);
  $('sheet').classList.add('account-editor-sheet');$('account-bank').value=bank?a.kind:(bankOptions[0]?.kind||'bank');$('account-type').value=a.type;
  let lastName=bank?(accountBank(a.kind)?.label||'银行卡'):({wechat:'微信',alipay:'支付宝',cash:'现金',custom:'其他账户'}[a.kind]||'其他账户');
  function updateKind(){
   const kind=$('account-kind').value;$('account-bank-field').hidden=kind!=='bank';
   $('sheet-content').querySelectorAll('[data-account-kind]').forEach(e=>e.classList.toggle('selected',e.dataset.accountKind===kind));
   const name=kind==='bank'?(accountBank($('account-bank').value)?.label||'银行卡'):({wechat:'微信',alipay:'支付宝',cash:'现金',custom:'其他账户'}[kind]||'其他账户');
   if(!$('account-name').value.trim()||$('account-name').value===lastName)$('account-name').value=name;lastName=name;updateNameCount();
  }
  $('account-kind').onchange=updateKind;$('account-bank').onchange=updateKind;
  $('sheet-content').querySelectorAll('[data-account-kind]').forEach(b=>b.onclick=()=>{$('account-kind').value=b.dataset.accountKind;updateKind()});
  const validate=()=>{const v=$('account-amount').value.trim(),error=!v?'请输入余额，可填 0':!/^-?\d+(\.\d{1,2})?$/.test(v)?'请输入有效余额，最多两位小数':Math.abs(Number(v))>999999999.99?'账户余额过大':'';$('account-amount-error').textContent=error;$('account-amount-error').hidden=!error;$('account-amount').setAttribute('aria-invalid',String(!!error));return !error};
  const nameError=message=>{const input=$('account-name');$('account-name-error').textContent=message;$('account-name-error').hidden=!message;input.setAttribute('aria-invalid',String(!!message));return !message};
  const nameTooLong=()=>Array.from($('account-name').value.trim()).length>nameLimit && !(id&&$('account-name').value===a.name);
  function updateNameCount(){const value=$('account-name').value.trim(),count=Array.from(value).length;$('account-name-count').textContent=`${count}/${nameLimit}${count>nameLimit&&!nameTooLong()?' · 原名称可保留':''}`;$('account-name-count').classList.toggle('over-limit',nameTooLong())}
  const validateName=()=>nameError(!$('account-name').value.trim()?'请输入账户名称':nameTooLong()?'账户名称最多 20 个字符':'');
  $('account-name').onblur=validateName;$('account-name').oninput=()=>{updateNameCount();if(nameTooLong()||$('account-name').getAttribute('aria-invalid')==='true')validateName()};updateNameCount();
  $('account-amount').onblur=validate;
  $('account-form').onsubmit=e=>{e.preventDefault();if(!validateName()){$('account-name').focus();return}if(!validate()){$('account-amount').focus();return}try{
   const kind=$('account-kind').value;parse(native.saveAccount(JSON.stringify({id:a.id,name:$('account-name').value,kind:kind==='bank'?$('account-bank').value:kind,type:kind==='bank'?$('account-type').value:id?a.type:'ASSET',amount:$('account-amount').value,description:$('account-description').value,includedInAssets:$('account-included').checked})));
   closeSheet();refresh();assets();if(id&&detail)renderDetail();notify('账户已保存');
  }catch(error){if(['账户名称已存在，请使用其他名称','账户名称最多 20 个字符'].includes(error.message)){nameError(error.message);$('account-name').focus()}else notify(error.message||'保存失败，内容已保留')}};
 }
 function open(id){
  const a=accounts.find(a=>a.id===id);if(!a){notify('账户已不存在');return}
  detail={uid:a.uid,month:localMonth(Date.now()),filter:'all',offset:0,rows:[]};go('account-detail');renderDetail();remember();
 }
 function renderDetail(reload=true,append=false){
  if(!detail)return;const a=find(detail.uid);if(!a){detail=null;go('assets');return}
  if(reload){if(!append)detail.offset=0;try{const result=parse(native.accountDetails(a.uid,detail.month,detail.filter,detail.offset));detail.rows=append?[...detail.rows,...result.rows]:result.rows;detail.hasMore=result.hasMore;detail.nextOffset=result.nextOffset;detail.expense=result.expenseCents;detail.income=result.incomeCents;detail.error=''}catch(error){detail.error=error.message}}
  $('account-detail').innerHTML=`<header class="account-detail-header"><button aria-label="返回我的资产" id="account-back">${icon('chevron-left')}<span>我的资产</span></button><h1>${escapeText(a.name)}</h1><button aria-label="账户操作" id="account-overflow">${icon('ellipsis')}</button></header>
   <section class="account-detail-balance"><div class="account-detail-identity">${symbol(a)}<span class="account-detail-name"><b>${escapeText(a.name)}</b><span class="account-detail-tags">${[accountBank(a.kind)||a.kind==='bank'?(a.type==='LIABILITY'?'信用卡':'储蓄卡'):({wechat:'微信账户',alipay:'支付宝账户',cash:'现金账户'}[a.kind]||(a.type==='LIABILITY'?'负债账户':'资产账户')),...accountDefaultRoles(a),...(a.includedInAssets===false?['不计入总资产']:[])].map(label=>`<span>${escapeText(label)}</span>`).join('')}</span></span></div><div class="account-detail-actions"><button id="account-add-bill" class="reference-primary">+ 记一笔</button><button id="account-calibrate">校准余额</button></div><div class="account-balance-line"><div><strong>${fmt(a.amountCents)}</strong><p>${a.type==='LIABILITY'?'当前账户待还金额':'当前账户可用余额'}</p></div></div><div class="account-month-totals"><div>本月支出<b>¥ ${money(detail.expense)}</b></div><div>本月收入<b>¥ ${money(detail.income)}</b></div></div></section>
   <div class="account-detail-toolbar"><button id="account-month">${detail.month.replace('-','年')}月 ${icon('chevron-down')}</button><div role="group" aria-label="账户明细筛选">${[['all','全部'],['expense','支出'],['income','收入'],['transfer','转账']].map(([value,label])=>`<button data-account-filter="${value}" class="${detail.filter===value?'active':''}" aria-pressed="${detail.filter===value}">${label}</button>`).join('')}</div></div>
   <div id="account-detail-rows">${detail.error?`<p class="empty-state" role="alert">${escapeText(detail.error)}<button id="account-retry">重新加载</button></p>`:detailRows()}</div>${detail.hasMore?'<button class="load-more-records" id="account-more">加载更多</button>':''}
   <footer class="account-detail-footer"><button class="reference-primary" id="account-add-expense">${icon('pen')} 为此账户记一笔支出</button></footer>`;
  $('account-back').onclick=()=>go('assets');$('account-overflow').onclick=overflow;$('account-calibrate').onclick=()=>edit(a.id);
  $('account-add-bill').onclick=$('account-add-expense').onclick=()=>{beginManual();RecordExperience.setManualAccount(a.uid);};
  $('account-month').onclick=()=>{showSheet('选择账户月份',`<input id="account-month-picker" class="month-input" type="month" min="2000-01" max="2100-12" value="${detail.month}"/><div class="sheet-actions"><button onclick="closeSheet()">取消</button><button id="account-month-apply" class="primary">确定</button></div>`);$('sheet').classList.add('native-picker-sheet');$('account-month-apply').onclick=()=>{const v=$('account-month-picker').value;if(!$('account-month-picker').checkValidity()||!v)return;detail.month=v;detail.offset=0;renderDetail();closeSheet();remember()}};
  $('account-detail').querySelectorAll('[data-account-filter]').forEach(b=>b.onclick=()=>{detail.filter=b.dataset.accountFilter;detail.offset=0;renderDetail();remember()});
  $('account-detail').querySelectorAll('[data-account-record]').forEach(b=>b.onclick=()=>openRecord(Number(b.dataset.accountRecord)));
  $('account-detail').querySelectorAll('[data-transfer-uid]').forEach(b=>b.onclick=()=>transfer(b.dataset.transferUid));
  if($('account-more'))$('account-more').onclick=()=>{detail.offset=detail.nextOffset;renderDetail(true,true);remember()};if($('account-retry'))$('account-retry').onclick=()=>renderDetail();
 }
 function detailRows(){let day='';return detail.rows.map(r=>{const date=localTime(r.occurredAt),key=date.slice(0,10),heading=key===day?'':`<h2 class="account-day">${key.replace(/-/g,'/')}</h2>`;day=key;const transfer=r.kind==='transfer',income=transfer?r.direction==='IN':r.direction==='INCOME',name=transfer?(income?'从 '+r.fromName+' 转入':'转至 '+r.toName):r.merchant;return heading+`<button class="account-detail-row" ${transfer?`data-transfer-uid="${r.uid}"`:`data-account-record="${r.id}"`}>${transfer?`<span class="category-icon">${icon('right-left')}</span>`:ledgerIcon(r)}<span><b>${escapeText(name)}</b><small>${date.slice(11)} · ${escapeText(r.category)}${r.requiresReimbursement?' · 待报销':''}</small></span><strong class="${income?'income':''}">${signedMoney(r.amountCents,income)}</strong></button>`}).join('')||'<div class="empty-state">这个月还没有相关明细</div>'}
 function overflow(){const a=find(detail.uid);showSheet('账户操作',`<button class="sheet-row" id="account-edit-action">编辑账户 ${icon('pen')}</button><button class="sheet-row" id="account-transfer-action">账户间转账 ${icon('right-left')}</button><button class="sheet-row danger" id="account-delete-action">删除账户 ${icon('trash-can')}</button>`);$('account-edit-action').onclick=()=>edit(a.id);$('account-transfer-action').onclick=()=>transfer();$('account-delete-action').onclick=()=>{showSheet('删除这个账户？','<p class="sheet-copy">已有账单会保留；存在转账记录时，请先处理相关转账。</p><div class="sheet-actions"><button onclick="closeSheet()">取消</button><button class="primary" id="account-delete-confirm">删除账户</button></div>');$('account-delete-confirm').onclick=()=>{try{parse(native.deleteAccount(a.id));detail=null;closeSheet();refresh();go('assets');notify('账户已删除')}catch(e){notify(e.message)}}}}
 function transfer(uid=''){
  const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
  const hex=[...bytes].map(n=>n.toString(16).padStart(2,'0')).join(''),commandUid=uid||`${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
  let saving=false;
  let value=null;try{if(uid)value=parse(native.transferDetails(uid)).transfer}catch(e){notify(e.message);return}
  const options=selected=>accounts.map(a=>`<option value="${a.uid}" ${a.uid===selected?'selected':''}>${escapeText(a.name)}</option>`).join('');
  const from=value?.fromUid||detail?.uid||accounts[0]?.uid,to=value?.toUid||accounts.find(a=>a.uid!==from)?.uid;
  showSheet(uid?'转账详情':'账户间转账',`<form id="transfer-form" class="transfer-form"><p class="sheet-copy">同步更新两个账户余额，不计入收入和支出。</p><label>转出账户<select id="transfer-from">${options(from)}</select></label><label>转入账户<select id="transfer-to">${options(to)}</select></label><label>转账金额<input id="transfer-amount" inputmode="decimal" value="${value?value.amountCents/100:''}" placeholder="0" required/></label><label>交易时间<input type="datetime-local" id="transfer-time" value="${localTime(value?.occurredAt||Date.now())}" required/></label><label>备注<input id="transfer-note" maxlength="500" value="${escapeText(value?.note||'')}"/></label><p id="transfer-error" role="alert" hidden></p><div class="sheet-actions">${uid?'<button type="button" id="transfer-delete">删除转账</button>':'<button type="button" onclick="closeSheet()">取消</button>'}<button class="primary" type="submit">保存转账</button></div></form>`);
  $('transfer-form').onsubmit=e=>{e.preventDefault();if(saving)return;saving=true;try{parse(native.saveTransfer(JSON.stringify({uid:commandUid,fromUid:$('transfer-from').value,toUid:$('transfer-to').value,amount:$('transfer-amount').value,occurredAt:new Date($('transfer-time').value).getTime(),note:$('transfer-note').value})));closeSheet();refresh();if(detail){detail.offset=0;renderDetail()}notify('转账已保存')}catch(error){saving=false;$('transfer-error').textContent=error.message;$('transfer-error').hidden=false}};
  if(uid)$('transfer-delete').onclick=()=>{showSheet('删除这笔转账？','<p class="sheet-copy">删除后将同时撤回两个账户的余额变动。</p><div class="sheet-actions"><button onclick="closeSheet()">取消</button><button class="primary" id="transfer-delete-confirm">删除转账</button></div>');$('transfer-delete-confirm').onclick=()=>{try{parse(native.deleteTransfer(uid));closeSheet();refresh();if(detail){detail.offset=0;renderDetail()}notify('转账已删除')}catch(e){notify(e.message)}}};
 }
 function state(){return detail?{uid:detail.uid,month:detail.month,filter:detail.filter}:null}
 function restore(value){if(!value||!find(value.uid))return;detail={uid:value.uid,month:/^\d{4}-\d{2}$/.test(value.month)?value.month:localMonth(Date.now()),filter:['all','expense','income','transfer'].includes(value.filter)?value.filter:'all',rows:[],offset:0};renderDetail()}
 function init(){$('add-account').innerHTML=icon('plus')+'<span>添加账户</span>';assets()}
 return {assets,edit,open,choose,symbol,parse,init,state,restore,renderDetail};
})();
