const {JSDOM,VirtualConsole}=require('jsdom');
const fs=require('fs'),{TextEncoder,TextDecoder}=require('util');
let errors=[];const vc=new VirtualConsole();vc.on('jsdomError',e=>errors.push(e.detail?.stack||e.message));
const html=fs.readFileSync(process.argv[2],'utf8');
const dom=new JSDOM(html,{url:'https://demo.invalid/',runScripts:'dangerously',pretendToBeVisual:true,virtualConsole:vc,beforeParse(w){
 Object.defineProperty(w.HTMLElement.prototype,'isContentEditable',{get(){return this.getAttribute('contenteditable')==='true'}});w.TextEncoder=TextEncoder;w.TextDecoder=TextDecoder;w.matchMedia=()=>({matches:false,addEventListener(){},removeEventListener(){}});w.scrollTo=()=>{};w.HTMLElement.prototype.scrollIntoView=function(){};
 w.ResizeObserver=class{observe(){}};w.HTMLDialogElement.prototype.showModal=function(){this.open=true};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'))};
 w.HTMLCanvasElement.prototype.getContext=()=>({clearRect(){},beginPath(){},moveTo(){},lineTo(){},stroke(){},arc(){},fill(){}});
 w.setTimeout=()=>0;w.clearTimeout=()=>{};
}});
const w=dom.window,d=w.document;const click=s=>d.querySelector(s).click();
function input(s){let e=d.querySelector('#draft');e.value=s;e.dispatchEvent(new w.Event('input',{bubbles:true}));}
console.log('errors',JSON.stringify(errors));if(errors.length)process.exit(1);
click('#take');click('#menuHan');
input('guaijia');
const country=Array.from(d.querySelectorAll('#candidates button strong')).map(e=>e.textContent);
if(country[0]!=='国家'||country.filter(x=>x==='国家').length!==1||country.includes('怪家'))throw Error('incorrect 国家 candidate: '+country);
// Number keys choose candidates on the current page, without entering a digit.
const note=d.querySelector('#result');note.focus();note.click();
for(const digit of ['1','2','3','4']){
  note.value='';note.setSelectionRange(0,0);input('ga');
  const expected=d.querySelectorAll('#candidates button strong')[Number(digit)-1].textContent;
  const event=new w.KeyboardEvent('keydown',{key:digit,bubbles:true,cancelable:true});
  d.querySelector('#draft').dispatchEvent(event);
  if(!event.defaultPrevented || note.value!==expected)throw Error('number '+digit+' failed: '+note.value+' / '+expected);
}
note.value='';note.setSelectionRange(0,0);input('ga');click('#nextCandidates');
const nextPageFirst=d.querySelector('#candidates button strong').textContent;
d.querySelector('#draft').dispatchEvent(new w.KeyboardEvent('keydown',{key:'1',bubbles:true,cancelable:true}));
if(note.value!==nextPageFirst)throw Error('number selection ignored current page');
note.value='';note.setSelectionRange(0,0);input('');
const plainDigit=new w.KeyboardEvent('keydown',{key:'1',bubbles:true,cancelable:true});note.dispatchEvent(plainDigit);
if(plainDigit.defaultPrevented)throw Error('ordinary numeric text was intercepted');
input('ga');
const modifiedDigit=new w.KeyboardEvent('keydown',{key:'1',ctrlKey:true,bubbles:true,cancelable:true});d.querySelector('#draft').dispatchEvent(modifiedDigit);
if(modifiedDigit.defaultPrevented)throw Error('modified keyboard shortcut was intercepted');
for(const [s,want] of [['haf','下'],['xiaf','夏'],['kir','˻克移˼']]){input(s);const visible=d.querySelector('#candidates').textContent;if(!visible.includes(want))throw Error('missing '+s+' / '+want);}
const arr=Array.from(d.querySelectorAll('#candidates button'));const card=arr.find(x=>x.querySelector('strong')?.textContent==='˻克移˼');if(!card)throw Error('missing paired candidate');card.click();
console.log('note',d.querySelector('#result').textContent,'group',d.querySelector('.joined-han')?.textContent);
if(d.querySelector('#result').textContent!=='˻克移˼')throw Error('bracket commit failed');
if(d.querySelector('#result .joined-han')?.textContent!=='克移')throw Error('missing style group');
// Keep distinct tones and meanings visible while collapsing true duplicates.
const result=d.querySelector('#result');result.value='树树';result.focus();result.setSelectionRange(2,2);
click('#handMode');
const cards=Array.from(d.querySelectorAll('#candidates button'));
const matches=cards.filter(e=>e.querySelector('strong')?.textContent==='树树');
if(matches.length!==3)throw Error('expected three distinct handwritten readings: '+matches.length);
const shown=matches.map(e=>e.querySelector('small').textContent);
for(const text of ['雪súsú','雪súsū','月亮sǔsǔ'])if(!shown.includes(text))throw Error('missing reading '+text+': '+shown);
if(!cards.some(e=>e.querySelector('strong')?.textContent==='树'))throw Error('missing shorter suffix');
matches.find(e=>e.querySelector('small').textContent==='雪súsú').click();
const details=d.querySelector('#readMeaning').textContent;
if(!details.includes('súsú')||!details.includes('雪'))throw Error('missing reading details: '+details);
click('#readChoose');
if(result.value!=='树树')throw Error('viewing a match modified the note');
result.value='';result.setSelectionRange(0,0);
w.eval("acceptGlyph('斯')");
const handCards=w.eval('candidateItems.map(c=>({form:c.form, meaning:c.meaning, roman:c.roman, source:c.source}))');
if(result.value!=='斯')throw Error('handwritten glyph was not committed');
if(handCards.filter(c=>c.form==='斯' && c.meaning==='肉' && c.roman==='sǐ').length!==1)throw Error('missing or duplicated 斯／肉: '+JSON.stringify(handCards));
if(handCards.some(c=>c.form==='石' && c.meaning.includes('肉')))throw Error('duplicate 石／肉: '+JSON.stringify(handCards));
const grouped=w.eval(`groupHandMatches([
  {ids:'a',length:1,meaning:'肉',short:'肉',kind:0},
  {ids:'b',length:1,meaning:'肉',short:'肉',kind:0},
  {ids:'c',length:1,meaning:'肉',short:'肉',kind:0}
],new Map([['a','sǐ'],['b','sǐ'],['c','sí']]),'斯')`);
if(grouped.length!==2 || grouped.map(c=>c.roman).join(',')!=='sǐ,sí')throw Error('incorrect matching by tone');
console.log('DOM checks passed');dom.window.close();
