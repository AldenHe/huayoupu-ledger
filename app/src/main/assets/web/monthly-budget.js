'use strict';
window.MonthlyBudget=(()=>{
 let cents=0,latest=null,animation=null,animationKey='';
 const seen=new Map();
 const maximum=99999999999;
 function update(value){cents=Number.isSafeInteger(value)&&value>0&&value<=maximum?value:0}
 function render(expense,income,scope=''){
  latest={expense,income,scope};
  const control=document.getElementById('monthly-budget');control.hidden=income;
  if(income){stop();return}
  const used=Math.max(0,Number(expense)||0),ratio=cents?used/cents:0,over=used>cents;
  control.classList.toggle('over-budget',!!cents&&over);
  control.classList.toggle('budget-unset',!cents);
  control.querySelector('.budget-label').textContent=cents?`月限额 ¥ ${money(cents)}`:'设置支出限额';
  control.querySelector('.budget-status').textContent=cents?(over?`已超 ¥ ${money(used-cents)}`:`剩余 ¥ ${money(cents-used)}`):'每月沿用';
  const meter=control.querySelector('.budget-meter');meter.hidden=!cents;
  meter.setAttribute('aria-valuemax',String(cents||1));meter.setAttribute('aria-valuenow',String(Math.min(used,cents)));
  meter.setAttribute('aria-label',cents?`月限额已用 ${(ratio*100).toFixed(0)}%，${over?'已超支':'剩余'+money(cents-used)+'元'}`:'未设置月限额');
  const fill=meter.firstElementChild,target=Math.min(1,ratio),key=`${scope}:${cents}`,next=`${key}:${target}`;
  if(animation&&animationKey===next)return;
  stop();fill.style.transform=`scaleX(${target})`;
  if(!cents||document.hidden||!control.getClientRects().length)return;
  const previous=seen.get(key)||0;
  if(previous===target)return;
  if(matchMedia('(prefers-reduced-motion: reduce)').matches||window.LedgerNative?.reportReduceMotion?.()||!fill.animate){seen.set(key,target);return}
  animationKey=next;
  animation=fill.animate([{transform:`scaleX(${previous})`},{transform:`scaleX(${target})`}],{duration:700,easing:'cubic-bezier(.16,1,.3,1)'});
  animation.onfinish=()=>{seen.set(key,target);animation=null;animationKey=''};
 }
 function stop(){if(animation){animation.cancel();animation=null}animationKey=''}
 function open(){if(latest)render(latest.expense,latest.income,latest.scope)}
 function edit(){
  showSheet('每月支出限额',`<form id="budget-form"><p class="sheet-copy" id="budget-help">设置一次，每月沿用。按所选月份的已入账支出计算，退款按账单实付金额计入。</p><label class="budget-field" for="budget-amount">月限额（元）<input id="budget-amount" type="text" inputmode="decimal" autocomplete="off" maxlength="13" placeholder="输入每月限额" value="${cents?String(cents/100):''}" aria-describedby="budget-help budget-error"/></label><p id="budget-error" class="budget-error" role="alert" hidden></p><div class="sheet-actions"><button type="button" id="budget-clear" ${cents?'':'hidden'}>移除限额</button><button class="primary" type="submit">保存限额</button></div></form>`);
  function save(amount){
   const error=document.getElementById('budget-error'),input=document.getElementById('budget-amount');
   try{
    if(!native?.setMonthlyBudget)throw Error('请在更新后的 Android 应用中设置月限额');
    const result=JSON.parse(native.setMonthlyBudget(amount));if(!result.ok)throw Error(result.error||'保存失败，请重试');
    update(result.monthlyBudgetCents);viewKey='';refresh();closeSheet();notify(amount?'月限额已保存，每月沿用':'已移除月限额');
   }catch(e){error.textContent=e.message||'保存失败，请重试';error.hidden=false;input.setAttribute('aria-invalid','true');input.focus()}
  }
  document.getElementById('budget-form').onsubmit=e=>{
   e.preventDefault();const input=document.getElementById('budget-amount'),value=input.value.trim();
   const parts=value.split('.'),parsed=Number(parts[0])*100+Number((parts[1]||'').padEnd(2,'0'));
   if(!/^\d+(\.\d{1,2})?$/.test(value)||!Number.isSafeInteger(parsed)||parsed<=0||parsed>maximum){const error=document.getElementById('budget-error');error.textContent='请输入大于零、最多两位小数的金额（最高 999,999,999.99 元）';error.hidden=false;input.setAttribute('aria-invalid','true');input.focus();return}
   save(value);
  };
  document.getElementById('budget-clear').onclick=()=>save('');
 }
 return {update,render,edit,open};
})();
