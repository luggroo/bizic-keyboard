// The local Ismantic model uses a 64×64 white-on-black image.
const modelLabels=Array.from(tujiaHandData.labels.trim());
const modelKnown=new Set(modelLabels);
let handwritingSession;
function decodeHandBytes(s){return Uint8Array.from(atob(s),c=>c.charCodeAt(0))}
async function openHandModel(){
  if(!handwritingSession)handwritingSession=(async()=>{
    const moduleURL=URL.createObjectURL(new Blob([decodeHandBytes(tujiaHandData.mjs)],{type:'text/javascript'}));
    ort.env.wasm.numThreads=1;
    ort.env.wasm.wasmPaths={mjs:moduleURL};
    ort.env.wasm.wasmBinary=decodeHandBytes(tujiaHandData.wasm);
    return ort.InferenceSession.create(decodeHandBytes(tujiaHandData.model),{executionProviders:['wasm']});
  })();
  return handwritingSession;
}
function handImage(paths,width,height){
  if(paths.reduce((n,p)=>n+p.length,0)<2)return null;
  const scale=360/Math.min(width,height),ox=0,oy=0;
  const canvas=document.createElement('canvas');canvas.width=Math.ceil(width*scale);canvas.height=Math.ceil(height*scale);
  const ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.fillStyle='black';ctx.fillRect(0,0,canvas.width,canvas.height);
  ctx.strokeStyle='white';ctx.fillStyle='white';ctx.lineWidth=360*.044;ctx.lineCap='round';ctx.lineJoin='round';
  for(const path of paths){
    if(!path.length)continue;
    ctx.beginPath();ctx.moveTo(ox+path[0][0]*scale,oy+path[0][1]*scale);
    for(const [x,y] of path.slice(1))ctx.lineTo(ox+x*scale,oy+y*scale);
    if(path.length===1){ctx.arc(ox+path[0][0]*scale,oy+path[0][1]*scale,ctx.lineWidth/2,0,2*Math.PI);ctx.fill()}
    else ctx.stroke();
  }
  const image=ctx.getImageData(0,0,canvas.width,canvas.height).data;let left=canvas.width,top=canvas.height,right=-1,bottom=-1;
  for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++)if(image[(y*canvas.width+x)*4]>0){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y)}
  if(right<left)return null;
  const w=right-left+1,h=bottom-top+1,s=56/Math.max(w,h),dw=Math.max(1,Math.round(w*s)),dh=Math.max(1,Math.round(h*s));
  const small=document.createElement('canvas');small.width=small.height=64;
  const sc=small.getContext('2d',{willReadFrequently:true});sc.fillStyle='black';sc.fillRect(0,0,64,64);
  sc.drawImage(canvas,left,top,w,h,Math.floor((64-dw)/2),Math.floor((64-dh)/2),dw,dh);
  const pixels=sc.getImageData(0,0,64,64).data,values=new Float32Array(4096);
  for(let i=0;i<values.length;i++)values[i]=pixels[i*4]/255;
  return values;
}
async function recognizeHand(paths,width,height){
  const image=handImage(paths,width,height);if(!image)return [];
  const session=await openHandModel();
  const result=await session.run({image:new ort.Tensor('float32',image,[1,1,64,64])});
  const scores=result[session.outputNames[0]].data;
  const ranking=Array.from(scores,(_,i)=>i).sort((a,b)=>scores[b]-scores[a]);
  const extra=core.recognize(paths).filter(c=>!modelKnown.has(c)).slice(0,2);
  return [...ranking.slice(0,6-extra.length).map(i=>modelLabels[i]),...extra];
}
