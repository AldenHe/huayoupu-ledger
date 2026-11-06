/* Shared mint scrolling: platform scroll gestures, authored clipped track and thumb. */
const UiScroll=(()=>{
 const mounted=new Set();let nextId=0;
 function mount(viewport,maxRows=5){
  const frame=document.createElement('div');frame.className='ui-scroll';viewport.before(frame);frame.append(viewport);
  viewport.classList.add('ui-scroll-viewport');if(!viewport.id)viewport.id='ui-scroll-'+(++nextId);
  const track=document.createElement('div');track.className='ui-scroll-track';track.tabIndex=0;track.setAttribute('role','scrollbar');track.setAttribute('aria-label','滚动账户列表');track.setAttribute('aria-controls',viewport.id);track.setAttribute('aria-orientation','vertical');
  const thumb=document.createElement('span');thumb.className='ui-scroll-thumb';track.append(thumb);frame.append(track);
  function update(){
   if(!frame.isConnected)return;
   const rows=[...viewport.children],last=rows[Math.min(maxRows,rows.length)-1];
   const limit=last?last.offsetTop+last.offsetHeight-rows[0].offsetTop:0;
   frame.style.height=Math.min(limit,innerHeight*.55)+'px';
   const range=Math.max(0,viewport.scrollHeight-viewport.clientHeight);track.hidden=range<1;const height=track.clientHeight;
   track.setAttribute('aria-valuemin','0');track.setAttribute('aria-valuemax',String(range));track.setAttribute('aria-valuenow',String(Math.round(viewport.scrollTop)));
   const size=Math.min(height,Math.max(24,height*viewport.clientHeight/Math.max(1,viewport.scrollHeight)));
   thumb.style.height=size+'px';thumb.style.transform='translateY('+(range?(height-size)*viewport.scrollTop/range:0)+'px)';
  }
  function seek(y){const r=track.getBoundingClientRect(),h=thumb.getBoundingClientRect().height;viewport.scrollTop=(viewport.scrollHeight-viewport.clientHeight)*Math.max(0,Math.min(1,(y-r.top-h/2)/Math.max(1,r.height-h)))}
  let dragging=false;
  track.onpointerdown=e=>{e.preventDefault();dragging=true;track.setPointerCapture(e.pointerId);seek(e.clientY)};
  track.onpointermove=e=>{if(dragging)seek(e.clientY)};track.onpointerup=track.onpointercancel=()=>{dragging=false};
  track.onkeydown=e=>{const steps={ArrowDown:40,ArrowUp:-40,PageDown:viewport.clientHeight,PageUp:-viewport.clientHeight,Home:-viewport.scrollHeight,End:viewport.scrollHeight};if(e.key in steps){e.preventDefault();viewport.scrollTop+=steps[e.key]}};
  viewport.addEventListener('scroll',update,{passive:true});
  const observer=new ResizeObserver(update);observer.observe(viewport);[...viewport.children].forEach(row=>observer.observe(row));
  const item={frame,destroy:()=>{observer.disconnect();viewport.removeEventListener('scroll',update);mounted.delete(item)}};mounted.add(item);requestAnimationFrame(update);return item;
 }
 function clear(root){[...mounted].filter(item=>root.contains(item.frame)).forEach(item=>item.destroy())}
 return {mount,clear};
})();
