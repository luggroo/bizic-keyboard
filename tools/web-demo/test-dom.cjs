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
const result=d.querySelector('#result');
const outputs=[['ye','bifzixkar','用叶氏拼音'],['diacritic','bízīkǎ','用带调号拼音'],['han','秘兹佧','用汉字谐音']];
function checkButtons(group){
  const keys=Array.from(d.querySelector(group).children);
  if(keys.length!==3)throw Error('missing output choices in '+group);
  outputs.forEach(([form,sample,name],i)=>{
    const key=keys[i];
    if(key.dataset.form!==form || key.firstElementChild.textContent!==sample || key.lastElementChild.textContent!==name ||
       key.getAttribute('aria-label')!==name+'，'+sample)throw Error('incorrect output key in '+group+': '+key.outerHTML);
  });
}
function chooseForm(group,form){click(group+' [data-form="'+form+'"]')}
function candidate(form){
  const card=[...d.querySelectorAll('#candidates .word')].find(e=>e.querySelector('strong')?.textContent===form);
  if(!card)throw Error('Missing visible candidate '+form+' in '+d.querySelector('#candidates').textContent);
  return card;
}
checkButtons('#menuOutputs');checkButtons('#orthography');checkButtons('#typingOutputs');
click('#tryForms');
if(d.querySelector('#kbMorePanel').hidden || d.querySelector('#draft').value!=='bizika')throw Error('Missing example draft or picker');
for(const [form,sample] of outputs){
  if(d.querySelector('#kbMorePanel').hidden){input('bizika');click('#kbMore')}
  const before=result.value;
  chooseForm('#menuOutputs',form);
  if(d.querySelector('#draft').value!=='bizika' || before!==result.value)throw Error('Switch lost input');
  const card=candidate(sample);
  if(!card.querySelector('small').textContent.includes('土家族'))throw Error('Missing meaning for '+sample);
  card.click();
  if(!result.value.endsWith(sample) || !result.value.startsWith(before))throw Error('Incorrect commit: '+result.value);
}
input('bizika');click('#openSettings');chooseForm('#orthography','diacritic');
if(d.querySelector('#orthography [aria-pressed=true]').dataset.form!=='diacritic' ||
   d.querySelector('#draft').value!=='bizika' || w.localStorage.getItem('tujia-orthography')!=='diacritic')throw Error('Settings selection failed');
d.querySelector('#settingsPanel').close();candidate('bízīkǎ');
for(const [form,sentence] of [['ye','ngaf yer gaf。'],['diacritic','ngá yě gá。'],['han','牙叶架。']]){
  click('#kbMore');chooseForm('#menuOutputs',form);click('#showScene');candidate(sentence).click();
  if(!result.value.endsWith(sentence))throw Error('Sentence practice ignored '+form);
}
click('#kbMore');chooseForm('#menuOutputs','diacritic');click('#tryNext');candidate('bōlǐ');
click('#kbMore');click('#menuHan');input('');result.value='';result.setSelectionRange(0,0);
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
result.value='树树';result.focus();result.setSelectionRange(2,2);
click('#handMode');
click('#kbMore');click('#menuHan');
const cards=Array.from(d.querySelectorAll('#candidates button'));
const matches=cards.filter(e=>e.querySelector('strong')?.textContent==='树树');
if(matches.length!==3)throw Error('expected three distinct handwritten readings: '+matches.length);
const shown=matches.map(e=>e.querySelector('small').textContent);
for(const text of ['雪súsú','雪súsū','月亮sǔsǔ'])if(!shown.includes(text))throw Error('missing reading '+text+': '+shown);
if(!cards.some(e=>e.querySelector('strong')?.textContent==='树'))throw Error('missing shorter suffix');
matches.find(e=>e.querySelector('small').textContent==='雪súsú').click();
const details=d.querySelector('#readMeaning').textContent;
if(!details.includes('súsú')||!details.includes('雪'))throw Error('missing reading details: '+details);
click('#readBack');
if(result.value!=='树树')throw Error('viewing a match modified the note');
for(const [form,sample] of outputs){
  result.value='前秘兹佧后';result.setSelectionRange(4,4);click('#handMode');
  candidate('秘兹佧').click();checkButtons('#readOutputs');chooseForm('#readOutputs',form);
  if(result.value!=='前'+sample+'后' || result.selectionStart!==1+sample.length)throw Error('Incorrect handwritten range: '+result.value);
  if(d.querySelector('#handMode').getAttribute('aria-pressed')!=='true' ||
     d.querySelector('#menuOutputs [aria-pressed=true]').dataset.form!=='han')throw Error('One-off conversion changed handwriting mode');
}
result.value='前波粒后';result.setSelectionRange(3,3);click('#handMode');
candidate('波粒').click();chooseForm('#readOutputs','han');
if(result.value!=='前波俐后')throw Error('Handwritten alias was not normalized');
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
console.log('DOM checks passed');
// Vowel keys use visible tone hints only in Han and marked spelling modes.
click('#typeMode');click('#kbMore');chooseForm('#menuOutputs','diacritic');
const vowel=d.querySelector('#keys .key[data-letter="a"]');
if(vowel.getAttribute('aria-label')!=='a'||!vowel.classList.contains('tone-enabled'))throw Error('marked vowel key lacks tone hints');
if([...vowel.querySelectorAll('.tone-hint')].map(x=>x.textContent).join('')!=='ˉˊˇˋ')throw Error('missing tone symbols');
vowel.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true,cancelable:true}));
if(d.querySelector('#toneKeys').hidden||d.querySelectorAll('#toneKeys .tone-key').length!==5)throw Error('tone picker did not open');
d.querySelectorAll('#toneKeys .tone-key')[3].click();
if(!d.querySelector('#draft').value.endsWith('à'))throw Error('tone selection did not enter vowel');
click('#kbMore');chooseForm('#menuOutputs','ye');
if(vowel.classList.contains('tone-enabled'))throw Error('Ye mode must not show tone hints');
vowel.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true,cancelable:true}));
if(!d.querySelector('#toneKeys').hidden)throw Error('Ye mode must not open tone picker');
console.log('Tone picker, hints and Ye mode passed');dom.window.close();
