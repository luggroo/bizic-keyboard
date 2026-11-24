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
for(const [s,want] of [['haf','下'],['xiaf','夏'],['kir','˻克移˼']]){input(s);const visible=d.querySelector('#candidates').textContent;if(!visible.includes(want))throw Error('missing '+s+' / '+want);}
const arr=Array.from(d.querySelectorAll('#candidates button'));const card=arr.find(x=>x.querySelector('strong')?.textContent==='˻克移˼');if(!card)throw Error('missing paired candidate');card.click();
console.log('note',d.querySelector('#result').textContent,'group',d.querySelector('.joined-han')?.textContent);
if(d.querySelector('#result').textContent!=='˻克移˼')throw Error('bracket commit failed');
if(d.querySelector('#result .joined-han')?.textContent!=='克移')throw Error('missing style group');
console.log('DOM checks passed');dom.window.close();
