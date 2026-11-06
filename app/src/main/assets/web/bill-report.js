/* A local report is an immutable snapshot: filter, print, then save. */
const BillReportUI=(()=>{
 let criteria=null,data=null,view=null,request='',shown=6,animation=null,exporting=false,picker=null;
 const element=id=>document.getElementById(id);
 const date=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
 const parse=s=>{const [y,m,d]=s.split('-').map(Number);return new Date(y,m-1,d)};
 const defaults=()=>{const now=new Date();return {from:date(new Date(now.getFullYear(),now.getMonth(),1)),through:date(new Date(now.getFullYear(),now.getMonth()+1,0)),tags:[],matchAll:false,direction:'',category:'',reimbursement:''}};
 const checkIcon='<i class="fa-solid fa-check" aria-hidden="true"></i>';
 function normalizedCriteria(value){
  const result={...defaults(),...value};
  if(result.tags.includes('报销')){result.tags=result.tags.filter(tag=>tag!=='报销');if(!result.reimbursement)result.reimbursement='required'}
  return result;
 }

 function read(){
  if(!element('report-form'))return criteria||defaults();
  return {from:element('report-from').value,through:element('report-through').value,
   tags:[...criteria.tags],
   matchAll:element('report-match').value==='all',direction:element('report-direction').value,category:element('report-category').value,reimbursement:element('report-reimbursement').value};
 }
 function shell(html,full=false){
  closePicker(false);showSheet('账单汇总',`<div id="report-root" class="bill-report-root${full?' report-fullscreen':''}">${html}</div>`);
  element('sheet').classList.add('bill-report-sheet');element('sheet').classList.toggle('bill-receipt-screen',full);
  if(full)FluidMotion.stop(element('sheet'));
 }
 function range(kind){
  const now=new Date(),first=kind==='recent'?new Date(now.getFullYear(),now.getMonth(),now.getDate()-29):new Date(now.getFullYear(),now.getMonth()-(kind==='previous'?1:0),1);
  return [date(first),date(kind==='recent'?now:new Date(first.getFullYear(),first.getMonth()+1,0))];
 }
 function updateDates(){
  element('report-from-value').textContent=element('report-from').value.replace(/-/g,'.');element('report-through-value').textContent=element('report-through').value.replace(/-/g,'.');
  document.querySelectorAll('[data-report-range]').forEach(b=>{const [a,z]=range(b.dataset.reportRange);b.setAttribute('aria-pressed',String(a===element('report-from').value&&z===element('report-through').value))});
 }
 function updateFilters(){
  const c=read(),summary=[];
  if(c.category)summary.push(c.category);
  if(c.reimbursement)summary.push(c.reimbursement==='required'?'需要报销':'不需要报销');
  if(c.direction)summary.push(c.direction==='INCOME'?'仅收入':'仅支出');
  if(c.tags.length)summary.push(`${c.tags.join('、')}${c.tags.length>1?(c.matchAll?'（全部满足）':'（任一）'):''}`);
  element('report-filter-summary').textContent=summary.join(' · ')||'全部账单';
  element('report-filters').classList.toggle('has-filters',summary.length>0);
 }
 function open(){
  criteria=normalizedCriteria(criteria);view='form';request='';exporting=false;
  let labels=[];try{labels=[...new Set([...BillLabels.known(),...criteria.tags])]}catch(error){notify(error.message);return}
  const categoryNames=[...new Set([...expenseCategories.map(c=>c[0]),...incomeCategories.map(c=>c[0]),...(customCategories.expense||[]),...(customCategories.income||[])])];
  const choices={
   'report-match':[['any','包含任一标签'],['all','全部标签都满足']],
   'report-direction':[['','全部收支'],['EXPENSE','仅支出'],['INCOME','仅收入']],
   'report-category':[['','全部分类'],...categoryNames.map(c=>[c,c])],
   'report-reimbursement':[['','全部账单'],['required','需要报销'],['notRequired','不需要报销']]
  };
  shell(`<form id="report-form" class="report-form" novalidate><div class="report-scroll"><p class="report-intro">选好范围，把账单整理成一张小票。</p><fieldset class="report-dates"><legend>时间范围</legend><div class="report-shortcuts" role="group" aria-label="日期快捷选择">${[['month','本月'],['previous','上月'],['recent','近 30 天']].map(([key,label])=>`<button type="button" data-report-range="${key}" aria-pressed="false">${label}</button>`).join('')}</div><div id="report-date-range" class="report-date-range" role="group" aria-label="汇总日期范围"><label class="report-date-endpoint"><small>开始日期</small><b id="report-from-value"></b><input id="report-from" type="date" value="${escapeText(criteria.from)}" min="2000-01-01" max="2100-12-31" required aria-label="选择开始日期"/></label><span class="report-date-link" aria-hidden="true"></span><label class="report-date-endpoint"><small>结束日期</small><b id="report-through-value"></b><input id="report-through" type="date" value="${escapeText(criteria.through)}" min="2000-01-01" max="2100-12-31" required aria-label="选择结束日期"/></label></div></fieldset>${['reimbursement','direction','category','match'].map(key=>`<input type="hidden" id="report-${key}" value="${escapeText(key==='match'?(criteria.matchAll?'all':'any'):criteria[key])}"/>`).join('')}<button id="report-filters" class="report-filter-trigger" type="button" aria-haspopup="dialog" aria-expanded="false"><span><b>筛选条件</b><small id="report-filter-summary"></small></span><i class="fa-solid fa-chevron-down" aria-hidden="true"></i></button><p class="report-scope">已入账 · 退款后金额 · 本机处理</p><p id="report-error" class="report-error" role="status"></p></div><div class="report-footer"><button id="report-generate" type="submit" class="primary">生成账单</button></div></form>`);
  updateDates();
  const dateRange=element('report-date-range');
  element('report-form').addEventListener('keydown',event=>{if(event.key==='Tab')dateRange.classList.add('keyboard-focus')});
  dateRange.addEventListener('pointerdown',()=>dateRange.classList.remove('keyboard-focus'));
  document.querySelectorAll('[data-report-range]').forEach(button=>button.onclick=()=>{
   const [first,last]=range(button.dataset.reportRange);element('report-from').value=first;element('report-through').value=last;updateDates();criteria=read();remember();
  });
  ['from','through'].forEach(key=>element('report-'+key).onchange=()=>{
   const field=element('report-'+key),other=element('report-'+(key==='from'?'through':'from'));
   if(!field.value||!field.checkValidity()){field.value=criteria[key];return}
   if(key==='from'?field.value>other.value:field.value<other.value)other.value=field.value;
   updateDates();element('report-error').textContent='';element('report-date-range').removeAttribute('aria-invalid');criteria=read();remember();
  });
  const trigger=element('report-filters');trigger.removeAttribute('aria-haspopup');trigger.setAttribute('aria-controls','report-filter-panel');
  const panel=document.createElement('div');panel.id='report-filter-panel';panel.className='report-filter-panel';panel.hidden=true;trigger.insertAdjacentElement('afterend',panel);
  trigger.onclick=()=>picker?closePicker():filters(choices,labels);updateFilters();
  // Inspect the target before a tag choice redraws its option buttons.
  element('report-form').addEventListener('click',event=>{if(picker&&!event.target.closest('#report-filters,#report-filter-panel'))closePicker(false)},true);
  element('report-form').oninput=element('report-form').onchange=()=>{criteria=read();remember()};
  element('report-form').onsubmit=event=>{
   event.preventDefault();criteria=read();const error=element('report-error');
   const valid=s=>/^20\d\d-\d\d-\d\d$|^2100-\d\d-\d\d$/.test(s)&&date(parse(s))===s;
   element('report-date-range').removeAttribute('aria-invalid');
   if(!valid(criteria.from)||!valid(criteria.through)||criteria.through<criteria.from){error.textContent='请选择有效日期，结束日期不能早于开始日期';element('report-date-range').setAttribute('aria-invalid','true');element('report-from').focus();return}
   if(!window.LedgerNative?.generateBillReport){error.textContent='请在 Android 应用中生成汇总';return}
   request=String(Date.now())+'-'+Math.random().toString(16).slice(2);error.textContent='正在整理账单…';element('report-generate').disabled=true;element('report-generate').textContent='正在汇总';remember();LedgerNative.generateBillReport(JSON.stringify(criteria),request);
  };remember();
 }
 function closePicker(restore=true){
  if(!picker)return false;const {node,trigger}=picker;picker=null;node.hidden=true;trigger.setAttribute('aria-expanded','false');if(restore&&trigger.isConnected)trigger.focus({preventScroll:true});return true;
 }
 function filters(choices,labels){
  const node=element('report-filter-panel'),trigger=element('report-filters');let group='category';
  node.hidden=false;picker={node,trigger};trigger.setAttribute('aria-expanded','true');
  node.innerHTML=`<div class="report-filter-columns"><div class="report-filter-groups" role="group" aria-label="筛选类别">${[['category','分类'],['reimbursement','报销'],['direction','收支'],['tags','标签']].map(([key,label])=>`<button type="button" data-filter-group="${key}" aria-controls="report-filter-values" aria-expanded="false"><span>${label}</span><i class="fa-solid fa-chevron-right" aria-hidden="true"></i></button>`).join('')}</div><div id="report-filter-values" class="report-select-options" role="group"></div></div><p id="report-filter-error" class="report-error" role="status" hidden></p><div class="report-filter-actions"><button id="report-filters-reset" type="button">重置筛选</button><button id="report-filters-close" type="button">收起<i class="fa-solid fa-chevron-up" aria-hidden="true"></i></button></div>`;
  function commit(){criteria=read();updateFilters();remember()}
  function draw(){
   node.querySelectorAll('[data-filter-group]').forEach(button=>button.setAttribute('aria-expanded',String(button.dataset.filterGroup===group)));
   const values=element('report-filter-values');values.setAttribute('aria-label',group==='tags'?'标签，可多选':({category:'分类',reimbursement:'报销',direction:'收支'})[group]);
   values.innerHTML=group==='tags'?`<p class="report-tag-hint">标签可多选</p>${labels.map(tag=>`<button type="button" data-report-tag="${escapeText(tag)}" aria-pressed="${criteria.tags.includes(tag)}"><span>${escapeText(tag)}</span>${checkIcon}</button>`).join('')||'<p class="report-no-tags">暂无自定义标签，可在账单详情里添加。</p>'}<div class="report-tag-match" role="group" aria-label="标签匹配" ${criteria.tags.length<2?'hidden':''}>${choices['report-match'].map(([value,text])=>`<button type="button" data-filter-key="matchAll" data-filter-value="${value}" aria-pressed="${criteria.matchAll===(value==='all')}"><span>${text}</span>${checkIcon}</button>`).join('')}</div>`:choices['report-'+group].map(([value,text])=>`<button type="button" data-filter-key="${group}" data-filter-value="${escapeText(value)}" aria-pressed="${criteria[group]===value}"><span>${escapeText(text)}</span>${checkIcon}</button>`).join('');
   values.querySelectorAll('[data-filter-key]').forEach(button=>button.onclick=()=>{
    const key=button.dataset.filterKey;const value=button.dataset.filterValue;
    element('report-'+(key==='matchAll'?'match':key)).value=value;commit();
    if(key==='matchAll'){draw();values.querySelector(`[data-filter-value="${value}"]`).focus({preventScroll:true})}else closePicker();
   });
   values.querySelectorAll('[data-report-tag]').forEach(button=>button.onclick=()=>{
    const tag=button.dataset.reportTag,index=criteria.tags.indexOf(tag),error=element('report-filter-error');
    if(index<0&&criteria.tags.length>=10){error.hidden=false;error.textContent='一次最多选择 10 个标签';return}
    if(index<0)criteria.tags.push(tag);else criteria.tags.splice(index,1);
    error.hidden=true;commit();const scroll=values.scrollTop;draw();values.scrollTop=scroll;
    [...values.querySelectorAll('[data-report-tag]')].find(item=>item.dataset.reportTag===tag)?.focus({preventScroll:true});
   });
  }
  node.querySelectorAll('[data-filter-group]').forEach(button=>button.onclick=()=>{group=button.dataset.filterGroup;draw()});
  element('report-filters-reset').onclick=()=>{['category','direction','reimbursement'].forEach(key=>element('report-'+key).value='');element('report-match').value='any';criteria.tags=[];commit();element('report-filter-error').hidden=true;draw()};
  element('report-filters-close').onclick=()=>closePicker();
  node.onkeydown=event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();closePicker()}};draw();
 }
 function rowMarkup(row){
  const time=new Intl.DateTimeFormat('zh-CN',{timeZone:data.zoneId,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(row.occurredAt));
  return `<li><div class="receipt-row-top"><b>${escapeText(row.merchant||'未填写商户')}</b><strong>${row.direction==='INCOME'?'+':'−'} ${money(row.amountCents)}</strong></div><p>${escapeText(time)} · ${escapeText(row.category)}${row.requiresReimbursement?' · 需要报销':''}</p>${row.tags.length?`<p class="receipt-labels">${row.tags.map(escapeText).join(' · ')}</p>`:''}${row.note?`<p class="receipt-note">${escapeText(row.note)}</p>`:''}</li>`;
 }
 function finishMotion(){
  if(animation){const active=animation;animation=null;active.cancel()}
  element('report-root')?.classList.remove('is-printing');
  const feed=element('report-root')?.querySelector('.receipt-window');if(feed)feed.style.removeProperty('height');
  if(element('report-skip'))element('report-skip').hidden=true;
  if(element('report-status')&&data)element('report-status').textContent=`${data.count} 笔账单已汇总`;
 }
 function receipt(animate){
  view='receipt';shown=Math.min(shown,data.count);exporting=false;const c=data.criteria;
  shell(`<div class="receipt-printer"><div class="report-toolbar"><button id="report-edit" type="button" aria-label="修改汇总条件"><i class="fa-solid fa-arrow-left" aria-hidden="true"></i></button><div><b>花有谱</b><span id="report-status" role="status">${animate?'正在出票…':data.count+' 笔账单已汇总'}</span></div><button id="report-close" type="button" aria-label="关闭账单汇总"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button></div><button id="report-skip" type="button" ${!animate?'hidden':''}>跳过动画</button><div class="printer-slot" aria-hidden="true"></div></div><div class="report-scroll receipt-scroll"><div class="receipt-window"><article class="report-receipt" id="report-receipt"><header><h3>花有谱 · 账单汇总</h3><p>${escapeText(c.from)} 至 ${escapeText(c.through)}</p><p>共 ${data.count} 笔 · 人民币</p>${c.reimbursement?`<p>报销：${c.reimbursement==='required'?'需要报销':'不需要报销'}</p>`:''}${c.tags.length?`<p>${c.matchAll?'全部满足':'包含任一'}：${c.tags.map(escapeText).join('、')}</p>`:''}${c.category?`<p>分类：${escapeText(c.category)}</p>`:''}${c.direction?`<p>${c.direction==='INCOME'?'仅收入':'仅支出'}</p>`:''}</header><dl class="receipt-totals"><div><dt>支出合计</dt><dd>¥ ${money(data.expense)}</dd></div><div><dt>收入合计</dt><dd>¥ ${money(data.income)}</dd></div><div><dt>结余</dt><dd>¥ ${money(data.income-data.expense)}</dd></div></dl><section class="receipt-categories"><h4>分类汇总</h4>${data.categories.map(category=>`<div><span>${escapeText(category.name)} · ${category.direction==='INCOME'?'收入':'支出'} <small>${category.count} 笔</small></span><b>¥ ${money(category.cents)}</b></div>`).join('')||'<p>此范围暂无已入账账单</p>'}</section><section class="receipt-items"><h4>账单明细</h4><ol id="report-rows">${data.rows.slice(0,shown).map(rowMarkup).join('')}</ol><p id="report-visible-count" class="receipt-page-count"></p><button id="report-more-rows" type="button" class="report-more-rows">查看更多明细</button></section><footer><p>金额为退款后的最终入账金额。</p><p>账本后续修改不改变本份汇总。</p></footer></article></div></div><div class="report-footer"><p id="report-save-status" class="report-save-status" role="status">包含全部 ${data.count} 笔 · 按所选格式保存，PDF 含图片 ZIP，Excel 内嵌图片</p><div class="report-export-actions"><button type="button" data-report-export="png"><i class="fa-regular fa-image" aria-hidden="true"></i>保存图片</button><button type="button" data-report-export="pdf"><i class="fa-regular fa-file-pdf" aria-hidden="true"></i>PDF 材料包</button><button type="button" data-report-export="xlsx"><i class="fa-regular fa-file-excel" aria-hidden="true"></i>保存 Excel</button></div></div>`,true);
  const updateCount=()=>{element('report-visible-count').textContent=data.count?`已展示 ${shown} / ${data.count} 笔`:'';element('report-more-rows').hidden=shown>=data.count};updateCount();
  element('report-edit').onclick=()=>{finishMotion();open()};element('report-close').onclick=()=>closeSheet(false);
  element('report-more-rows').onclick=()=>{
   finishMotion();const next=Math.min(shown+40,data.count);
   try{while(data.rows.length<next){const page=JSON.parse(LedgerNative.billReportPage(data.token,data.rows.length));if(!page.ok)throw new Error(page.error);if(!page.rows.length)throw new Error('明细读取失败，请重新生成');data.rows.push(...page.rows)}element('report-rows').insertAdjacentHTML('beforeend',data.rows.slice(shown,next).map(rowMarkup).join(''));shown=next;updateCount()}catch(error){element('report-save-status').textContent=error.message||'明细读取失败，请重试'}
  };
  document.querySelectorAll('[data-report-export]').forEach(button=>button.onclick=()=>{if(exporting)return;finishMotion();LedgerNative.exportBillReport(data.token,button.dataset.reportExport)});element('report-skip').onclick=finishMotion;
  if(animate){
   element('report-root').classList.add('is-printing');const feed=element('report-root').querySelector('.receipt-window');feed.style.height='0px';
   requestAnimationFrame(()=>{
    const paper=element('report-receipt');if(!paper||view!=='receipt'||!element('report-root').classList.contains('is-printing'))return;
    if(matchMedia('(prefers-reduced-motion: reduce)').matches||window.LedgerNative?.reportReduceMotion?.()||!feed.animate){finishMotion();return}
    // Abdul Qadir Khan's MIT receipt-printer reveals paper in 3px motor advances.
    // The whole page receives the paper; only the printer mouth occludes the feed.
    // Attribution remains in receipt-interaction-LICENSE.txt. Data never animates.
    const height=paper.offsetHeight+24,steps=48;
    const frames=Array.from({length:steps+1},(_,step)=>({height:`${Math.round(height*step/steps/3)*3}px`,offset:step/steps,easing:'steps(2,end)'}));
    animation=feed.animate(frames,{duration:1450});animation.onfinish=finishMotion;
   });
  }
  remember();
 }
 return {open,
  back:()=>closePicker(),
  state:()=>({criteria:read(),token:data?.token||'',view:element('report-root')&&element('sheet').open?view:null}),
  restore:state=>{if(!state)return;criteria=normalizedCriteria(state.criteria);if(state.view==='form'){open();return}if(state.view==='receipt'&&state.token){try{const snapshot=JSON.parse(LedgerNative.billReportPage(state.token,0));if(!snapshot.ok)throw new Error(snapshot.error);data=snapshot;shown=Math.min(6,data.count);receipt(false)}catch(_){open();element('report-error').textContent='上次汇总已过期，请重新生成'}}},
  closed:()=>{if(!view)return;criteria=read();closePicker(false);view=null;request='';finishMotion();element('sheet').classList.remove('bill-report-sheet','bill-receipt-screen')},
  result:result=>{if(result.requestId!==request||view!=='form'||!element('report-form'))return;request='';if(!result.ok){element('report-error').textContent=result.error||'汇总生成失败，请重试';element('report-generate').disabled=false;element('report-generate').textContent='生成账单';return}data=result;shown=Math.min(6,data.count);receipt(true)},
  exportResult:result=>{if(view!=='receipt'||!element('report-save-status'))return;exporting=!!result.busy;document.querySelectorAll('[data-report-export]').forEach(button=>button.disabled=exporting);element('report-save-status').textContent=result.error||result.message||''}
 };
})();
