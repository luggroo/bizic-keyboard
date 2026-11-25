// Check the embedded engine and model without making any network requests.
const assert=require('assert'),fs=require('fs'),path=require('path'),vm=require('vm'),{pathToFileURL}=require('url');
const root=path.resolve(__dirname,'../..'), web=path.resolve(process.argv[2]), dist=path.resolve(process.argv[3]);
const read=p=>fs.readFileSync(p,'utf8');
const ctx={console,atob,Uint8Array,Int32Array,Uint32Array,Float32Array,TextEncoder,TextDecoder,WebAssembly,Blob,
  URL:{createObjectURL:()=>pathToFileURL(path.join(dist,'ort-wasm-simd-threaded.mjs')).href},
  ort:require(path.join(dist,'ort.min.js'))};
vm.createContext(ctx);
vm.runInContext(read(path.join(web,'engine-data.js'))+'\n'+read(path.join(root,'tools/web-demo/ui/core-runtime.js'))+'\nthis.core=openTujiaCore(tujiaCoreData)',ctx);
const exact=(query,mode,style)=>ctx.core.lookup(query,mode,style).filter(c=>c.length===query.length).map(c=>c.form);
assert(exact('haf',1,0).includes('下'));assert(exact('xiaf',1,0).includes('夏'));
for(const onset of ['b','p','m','d','t','l','g','k','ng','h','z','c','s','r','w','y','zh','ch','sh']){
  for(const mode of [0,1])for(const style of [0,1]){
    assert.deepStrictEqual(Array.from(exact(onset+'o',mode,style)).sort(),Array.from(exact(onset+'uo',mode,style)).sort(),onset);
  }
}
assert.deepStrictEqual(Array.from(exact('gojia',1,0)).sort(),Array.from(exact('guojia',1,0)).sort());
for(const input of ['guaijia','guairjiax','guaifjiax','guijia','gojia','guojia','gorjia','guorjia']){
  const choices=exact(input,1,0);assert.equal(choices[0],'国家');assert(!choices.includes('怪家')&&!choices.includes('姑崴家'));
}
assert(exact('bifzixkar',0,0).includes('bifzixkar'));
assert(exact('bizika',0,1).includes('bízīkǎ'));assert(!exact('bifzixkar',0,1).includes('bízīkǎ'));
assert(ctx.core.handwrittenWords('玻离',1,0).some(c=>c.form==='波俐' && c.length===2));
vm.runInContext(read(path.join(root,'tools/web-demo/ui/notepad.js'))+'\nthis.format=bracketHanUnits',ctx);
const result=ctx.format(' 克移波俐',['克移','波','俐']);assert.equal(result.text,' ˻克移˼波俐');
vm.runInContext(read(path.join(web,'hand-data.js'))+'\n'+read(path.join(root,'tools/web-demo/ui/hand-model.js'))+'\nthis.openHandModel=openHandModel',ctx);
(async()=>{let model=await ctx.openHandModel();let output=await model.run({image:new ctx.ort.Tensor('float32',new Float32Array(4096),[1,1,64,64])});assert.equal(output[model.outputNames[0]].data.length,3755);console.log('Offline engine, dictionary, brackets and handwriting model passed')})().catch(e=>{console.error(e);process.exit(1)});
