/* Bill labels are independent of categories and accounting amounts. */
const BillLabels=(()=>{
 let editing=[];
 const known=()=>{
  if(!window.LedgerNative?.billLabels)return [];
  const result=JSON.parse(LedgerNative.billLabels());
  if(!result.ok)throw new Error(result.error||'标签读取失败');
  return result.tags;
 };
 const describe=tags=>tags.length?tags.join('、'):'添加标签';
 function choose(){
  let options;try{options=[...new Set([...known(),...editing])]}catch(error){notify(error.message);return}
  const selected=new Set(editing);
  showSheet('账单标签',`<p class="label-help">原分类继续保留，可选择多个标签。</p><div id="label-options" class="label-options"></div><form id="new-label-form" class="new-label-form"><label for="new-label">创建标签</label><div><input id="new-label" maxlength="24" placeholder="例如：差旅、项目A" autocomplete="off"/><button type="submit">添加</button></div><p id="label-error" role="status"></p></form><p class="label-help">每笔最多 10 个标签，每个最多 24 字。</p><div class="sheet-actions"><button id="labels-cancel" type="button">取消</button><button id="labels-apply" type="button" class="primary">使用这组标签</button></div>`);
  const draw=()=>{
   document.getElementById('label-options').innerHTML=options.map(tag=>`<button type="button" aria-pressed="${selected.has(tag)}" data-label="${escapeText(tag)}">${escapeText(tag)}</button>`).join('');
   document.querySelectorAll('[data-label]').forEach(button=>button.onclick=()=>{
    const tag=button.dataset.label;
    if(selected.has(tag))selected.delete(tag);
    else if(selected.size<10)selected.add(tag);
    else{document.getElementById('label-error').textContent='每笔最多选择 10 个标签';return}
    document.getElementById('label-error').textContent='';draw();
   });
  };
  draw();
  document.getElementById('new-label-form').onsubmit=event=>{
   event.preventDefault();const input=document.getElementById('new-label'),tag=input.value.trim(),error=document.getElementById('label-error');
   if(tag==='报销'){error.textContent='报销已是独立属性，请在账单详情里设置';input.focus();return}
   if(!tag||tag.length>24||/[\x00-\x1f\x7f-\x9f]/.test(tag)){error.textContent='请输入 1–24 字的标签，不含换行或特殊控制字符';input.focus();return}
   if(selected.size===10&&!selected.has(tag)){error.textContent='每笔最多选择 10 个标签';return}
   if(!options.includes(tag))options.push(tag);selected.add(tag);input.value='';error.textContent='';draw();
  };
  document.getElementById('labels-cancel').onclick=()=>closeSheet();
  document.getElementById('labels-apply').onclick=()=>{
   editing=[...selected];draft.tags=[...editing];
   document.getElementById('bill-labels-value').textContent=describe(editing);
   closeSheet();document.getElementById('bill-labels-editor').dispatchEvent(new Event('input',{bubbles:true}));remember();
  };
 }
 return {
  known,
  badge:record=>{
   const tags=record.tags||[];
   return tags.length?`<span class="bill-label-badge" aria-label="标签：${escapeText(tags.join('、'))}">${escapeText(tags[0])}${tags.length>1?' +'+(tags.length-1):''}</span>`:'';
  },
  read:()=>[...editing],
  renderEditor:(value,working)=>{
   editing=[...(value.tags||[])];
   document.getElementById('bill-labels-value').textContent=describe(editing);
   document.getElementById('bill-labels-button').disabled=working;
  },
  bind:()=>document.getElementById('bill-labels-button').onclick=choose
 };
})();
