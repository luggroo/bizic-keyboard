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
for(const [query, expected] of [
  ['bizika',['秘兹佧','币子佧']], ['bijika',['秘基佧','币基佧']], ['mijika',['密及佧','秘基佧']]]) {
  const forms=Array.from(exact(query,1,0)).filter(x=>expected.includes(x));
  assert.deepStrictEqual(forms,expected,query);
}
const ri=ctx.core.lookup('rǐ',0,1).filter(c=>c.form==='rǐ'&&c.length===2);
assert.equal(ri.length,1);assert.equal(ri[0].short,'多；男生殖器');
const sameReading=ctx.core.lookup('ngaf',0,0).filter(c=>c.length===4&&c.form==='ngaf');
assert.equal(sameReading.length,1);assert(sameReading[0].meaning.includes('我')&&sameReading[0].meaning.includes('压'));
for(const [query,readings] of [['bizika',['bifzixkar']],['bijika',['bifjixkar']],['mijika',['mirjirkar','mifjixkax']]]) {
  const forms=Array.from(exact(query,0,0));
  for(const reading of readings) assert.equal(forms.filter(x=>x===reading).length,1,query);
}
for(const [key,form] of [['max','马'],['mav','马'],['mar','麻'],['maf','祃']]){assert(exact(key,1,0).includes(form),key);assert(!exact(key,1,0).includes('妈'),key)}
assert(exact('ngaf',1,0).includes('牙'));assert(!exact('ngaf',1,0).includes('讶'));
assert(exact('lex',1,0).includes('咧'));
assert(exact('haf',1,0).includes('下'));assert(exact('xiaf',1,0).includes('夏'));
for(const onset of ['b','p','m','d','t','l','g','k','ng','h','z','c','s','r','w','y','zh','ch','sh']){
  for(const mode of [0,1])for(const style of [0,1]){
    assert.deepStrictEqual(Array.from(exact(onset+'o',mode,style)).sort(),Array.from(exact(onset+'uo',mode,style)).sort(),onset);
  }
}
for(const [a,b] of [['fa','hua'],['fu','hu'],['fo','huo'],['fen','feng'],['lin','ling'],['ling','leng'],['ding','deng'],['ting','teng']]){
  assert.deepStrictEqual(Array.from(exact(a,1,0)).sort(),Array.from(exact(b,1,0)).sort(),a+'/'+b);
}
assert(exact('ning',1,0).includes('宁'));assert(!exact('neng',1,0).includes('宁'));assert(!exact('ling',1,0).includes('宁'));
assert(exact('nen',1,0).includes('零'));assert(exact('neng',1,0).includes('零'));assert(exact('neng',1,0).includes('棱'));
assert(exact('ling',1,0).includes('零'));assert(exact('leng',1,0).includes('零'));
assert(exact('ling',1,0).includes('棱'));assert(exact('leng',1,0).includes('棱'));
assert(exact('lingchen',1,0).includes('凌晨'));assert(exact('lengchen',1,0).includes('凌晨'));
assert(exact('ningyuan',1,0).includes('宁愿'));assert(!exact('nengyuan',1,0).includes('宁愿'));
assert(exact('fanguo',1,0).includes('翻过'));assert(exact('huanguo',1,0).includes('翻过'));
assert.deepStrictEqual(Array.from(exact('gojia',1,0)).sort(),Array.from(exact('guojia',1,0)).sort());
for(const input of ['guaijia','guairjiax','guaifjiax','guijia','gojia','guojia','gorjia','guorjia']){
  const choices=exact(input,1,0);assert.equal(choices[0],'国家');assert(!choices.includes('怪家')&&!choices.includes('姑崴家'));
}
for(const input of ['nendao','nengdao','lindao','lingdao']){
  const choices=ctx.core.lookup(input,1,0).filter(c=>c.length===input.length);
  assert.equal(choices[0].form,'领导',input);assert(!choices.some(c=>c.form==='凛刀'),input);
}
for(const [input,word] of [['bangzang','班长'],['xiaoxiao','学校'],['biefdix','别的'],['befcaix','白菜'],['minrzif','名字'],['lorkuanr','箩筐']]){
  assert(exact(input,1,0).includes(word),input);
}
for (const [input,expected] of [['lenxzir','檩子'],['lenxzix','檩子'],['lenrzir','铃子']]){
  assert(exact(input,1,0).includes(expected),input);
}
for(const input of ['kovyir','koyi','kòyǐ']){
  const choices=ctx.core.lookup(input,1,0).filter(c=>c.length===input.length&&c.meaning==='可以');
  assert(choices.some(c=>c.form==='可以'&&c.loan),input);
  assert(!choices.some(c=>c.form==='可移'),input);
}
for(const [input,meaning,form] of [['huixmianr','面粉','灰面'],['maorlurzix','驴','毛驴子']]){
  const choices=ctx.core.lookup(input,1,0).filter(c=>c.length===input.length&&c.meaning===meaning);
  assert(choices.some(c=>c.form===form&&!c.loan),input);
}
const nenzi=Array.from(exact('nenzi',1,0));assert(nenzi.includes('檩子')&&nenzi.includes('铃子'));
assert(!nenzi.includes('冷直')&&!nenzi.includes('楞直'));
assert(exact('bifzixkar',0,0).includes('bifzixkar'));
assert(exact('bizika',0,1).includes('bízīkǎ'));assert(!exact('bifzixkar',0,1).includes('bízīkǎ'));
assert(ctx.core.handwrittenWords('玻离',1,0).some(c=>c.form==='波俐' && c.length===2));
const rareStroke=[[[13,31],[21,31],[42,26]],[[25,8],[29,13],[25,47],[18,64],[6,81]],
  [[32,38],[34,41],[33,64],[35,76],[44,83],[63,85],[83,79],[89,75],[90,62]],
  [[57,7],[61,12],[51,33],[40,47]],[[61,21],[72,34],[81,41],[93,43]],
  [[50,43],[54,47],[53,59],[49,67],[43,72]],[[65,40],[68,44],[68,77]]];
assert.deepStrictEqual(Array.from(ctx.core.recognizeStrong(rareStroke)),['尬']);
assert.deepStrictEqual(Array.from(ctx.core.recognizeStrong([[[15,40],[85,39]],[[48,8],[46,90]]])),[]);

vm.runInContext(read(path.join(root,'tools/web-demo/ui/notepad.js'))+'\nthis.format=bracketHanUnits',ctx);
const result=ctx.format(' 克移波俐',['克移','波','俐']);assert.equal(result.text,' ˻克移˼波俐');
vm.runInContext(read(path.join(web,'hand-data.js'))+'\n'+read(path.join(root,'tools/web-demo/ui/hand-model.js'))+'\nthis.openHandModel=openHandModel',ctx);
(async()=>{
  let model=await ctx.openHandModel();
  let output=await model.run({image:new ctx.ort.Tensor('float32',new Float32Array(4096),[1,1,64,64])});
  assert.equal(output[model.outputNames[0]].data.length,3755);
  const demo={tujiaHandData:{labels:'木大'},Float32Array,ort:{Tensor:function(){}},
    core:{recognize:()=>['尬','木'],recognizeStrong:()=>['尬']}};
  vm.createContext(demo);
  vm.runInContext(read(path.join(root,'tools/web-demo/ui/hand-model.js'))+
    `\nhandImage=()=>new Float32Array(4096);openHandModel=async()=>({outputNames:['out'],run:async()=>({out:{data:new Float32Array([2,1])}})});this.run=recognizeHand`,demo);
  assert.equal((await demo.run(rareStroke,100,100))[0],'尬');
  demo.core.recognizeStrong=()=>[];
  assert.equal((await demo.run(rareStroke,100,100))[0],'木');
  console.log('Offline engine, dictionary, brackets and handwriting model passed');
})().catch(e=>{console.error(e);process.exit(1)});
