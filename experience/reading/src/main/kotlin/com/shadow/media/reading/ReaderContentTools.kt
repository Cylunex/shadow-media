package com.shadow.media.reading

/** Runs in the publication document. Preview never navigates or changes the book locator. */
internal fun readerContentToolsScript() = """
(() => {
  if (window.shadowContentTools) return;
  window.shadowContentTools = true;
  let overlay = null, origin = null, generation = 0;
  const close = () => {
    generation++;
    if (overlay) overlay.remove();
    overlay = null;
    if (origin && origin.isConnected) origin.focus({preventScroll:true});
  };
  const show = (title, content) => {
    close();
    origin = document.activeElement;
    const dialog = document.createElement('dialog');
    dialog.setAttribute('aria-label', title);
    dialog.style.cssText = 'position:fixed;inset:6%;box-sizing:border-box;width:88%;max-height:88%;overflow:auto;padding:20px;border:1px solid GrayText;border-radius:12px;background:Canvas;color:CanvasText;z-index:2147483647;font:16px/1.7 sans-serif;';
    const button = document.createElement('button');
    button.textContent = '关闭，回到原文';
    button.style.cssText = 'display:block;min-height:48px;padding:8px 16px;margin-bottom:12px;';
    button.onclick = close;
    dialog.append(button, content);
    dialog.addEventListener('cancel', e => {e.preventDefault();close();});
    document.documentElement.append(dialog); overlay = dialog;
    dialog.showModal(); button.focus({preventScroll:true});
  };
  let bypass = false;
  window.addEventListener('click', async event => {
    if (bypass || overlay || event.defaultPrevented) return;
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return;
    const image = target.closest('img');
    if (image) {
      event.preventDefault();event.stopImmediatePropagation();
      const frame = document.createElement('div');
      frame.style.cssText='overflow:auto;max-height:70vh;touch-action:pan-x pan-y;';
      const copy = document.createElement('img');
      copy.src = image.currentSrc || image.src; copy.alt = image.alt || '书中插图';
      copy.style.cssText='display:block;width:100%;height:auto;max-width:none;transform-origin:center;';
      let zoom = 1, distance = 0, initial = 1;
      const span = touches => Math.hypot(touches[0].clientX-touches[1].clientX,touches[0].clientY-touches[1].clientY);
      frame.addEventListener('touchstart', e=>{if(e.touches.length===2){distance=span(e.touches);initial=zoom;}},{passive:true});
      frame.addEventListener('touchmove', e=>{if(e.touches.length===2&&distance){e.preventDefault();zoom=Math.max(1,Math.min(5,initial*span(e.touches)/distance));copy.style.width=(zoom*100)+'%';}},{passive:false});
      const zoomButton = document.createElement('button'); zoomButton.textContent='放大或还原插图';
      zoomButton.onclick=()=>{zoom=zoom===1?2:1;copy.style.width=(zoom*100)+'%';};
      frame.append(copy);const wrapper=document.createElement('div');wrapper.append(zoomButton,frame);
      show('插图预览',wrapper);return;
    }
    const link = target.closest('a[href]');
    if (!link || !((link.getAttribute('epub:type')||'').split(/\s+/).includes('noteref') || link.getAttribute('role')==='doc-noteref')) return;
    const url = new URL(link.href,location.href);
    if(url.origin!==location.origin || !url.hash) return;
    event.preventDefault();event.stopImmediatePropagation();
    const request = ++generation;
    try {
      let doc = document;
      if(url.pathname!==location.pathname) {
        const response = await fetch(url.href);
        if(!response.ok) throw Error('footnote');
        doc = new DOMParser().parseFromString(await response.text(),'text/html');
      }
      const note = doc.getElementById(decodeURIComponent(url.hash.slice(1)));
      if(!note) throw Error('missing footnote');
      if(request!==generation)return;
      const text = document.createElement('div');text.textContent=note.textContent;
      link.focus({preventScroll:true});show('脚注',text);
    } catch(e) {
      if(request!==generation)return;
      bypass=true;link.click();bypass=false;
    }
  },true);
})();
""".trimIndent()
