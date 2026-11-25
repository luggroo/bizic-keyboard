#!/usr/bin/env python3
"""Exercise the downloadable HTML with a real browser, with all network requests blocked."""
import argparse
import csv
import re
from pathlib import Path
from zipfile import ZipFile
from playwright.sync_api import sync_playwright

ap=argparse.ArgumentParser()
ap.add_argument('bundle',type=Path)
ap.add_argument('--chromium',default='/usr/bin/chromium')
ap.add_argument('--frames',type=Path)
ap.add_argument('--all-han',action='store_true',help='Sweep every lexicon spelling for duplicate Han cards')
args=ap.parse_args()
with ZipFile(args.bundle) as z:
    assert z.testzip() is None
    html=z.read('index.html').decode()
    assert '<script src=' not in html
frames=[]; root=Path(__file__).resolve().parents[2]
def snap(page,tag):
    if not args.frames:return
    args.frames.mkdir(parents=True,exist_ok=True)
    path=args.frames/(f'{len(frames):02d}-{tag}.png')
    page.screenshot(path=path)
    frames.append(path)
with sync_playwright() as pw:
    browser=pw.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
    context=browser.new_context(viewport={'width':390,'height':844},offline=True,locale='zh-CN')
    page=context.new_page();errors=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    def reset(fast=False):
        page.goto('about:blank')
        if fast:page.evaluate('window.setTimeout=((real)=>(fn,ms,...a)=>real(fn,Math.min(ms,4),...a))(window.setTimeout)')
        page.set_content(html,wait_until='load')
        assert not errors,errors
    def keyboard(mode,marked=False):
        reset()
        page.locator('#take').click()
        page.locator('#result').click()
        if mode==2:
            page.locator('#handMode').click()
        else:
            page.locator('#typeMode').click()
        page.locator('#kbMore').click()
        key = '#menuMarked' if marked and mode!=1 else '#menuSpell' if mode==0 else '#menuHan'
        page.locator(key).click()
    def text():return page.locator('#result').evaluate('e=>e.value')
    def choose(text,attr=''):
        card=page.locator('#candidates button'+attr).filter(has=page.locator('strong').filter(has_text=re.compile('^'+re.escape(text)+'$'))).first
        card.click()
    def visible_form(form):
        match=page.locator('#candidates .word').filter(has=page.locator('strong').filter(has_text=re.compile('^'+re.escape(form)+'$')))
        for _ in range(100):
            if match.count():return match.first
            following=page.locator('#nextCandidates')
            if not following.is_enabled():break
            following.click()
        raise AssertionError('Missing candidate '+form)
    def expected_sound(output_mode):
        keyboard(output_mode,True)
        page.locator('#draft').fill('bizika')
        choose('秘兹佧' if output_mode else 'bízīkǎ','[data-source="exact"]')
        snap(page,'after-word')
        card=page.locator('#candidates [data-source="sound"]').first
        form=card.locator('strong').inner_text();assert form==('萌' if output_mode else 'mǒng')
        assert card.locator('small').inner_text()==''
        before=text();card.click()
        assert text()==before+('' if output_mode else ' ')+form
        assert page.locator('#draft').input_value()==''
        snap(page,'after-first-prediction')
        if output_mode:
            assert page.evaluate("editorContext().ids==='' && editorContext().sound===''")
            assert page.locator('#candidates [data-source="sound"]').count()==0
            page.locator('#draft').fill('ga');choose('夹')
            assert text()==before+'萌夹'
            snap(page,'after-typed-ga')
            return form,'ga→夹'
        next_card=page.locator('#candidates [data-source="sound"]').first
        next_form=next_card.locator('strong').inner_text()
        assert next_form
        before=text();next_card.click()
        assert text()==before+('' if output_mode else ' ')+next_form
        assert page.locator('#draft').input_value()==''
        snap(page,'after-second-prediction')
        return form,next_form
    # Font preferences scale the text while the IME stays anchored to the same editor.
    preferences=context.new_cdp_session(page)
    for size in [14,16,21,26,32]:
        preferences.send('Page.setFontSizes',dict(fontSizes=dict(standard=size,fixed=size)))
        reset()
        actual_size=page.locator('#keys .key').first.evaluate('e=>parseFloat(getComputedStyle(e).fontSize)')
        assert abs(actual_size-size*19/16)<.05,(size,actual_size)
    preferences.send('Page.setFontSizes',dict(fontSizes=dict(standard=16,fixed=13)))
    preferences.send('Emulation.setPageScaleFactor',dict(pageScaleFactor=1.5))
    assert abs(page.evaluate('visualViewport.scale')-1.5)<.05
    preferences.send('Emulation.setPageScaleFactor',dict(pageScaleFactor=1))
    metrics=[]
    for width,height in [(390,844),(320,568)]:
      for percent in [85,100,130,160,200]:
        page.set_viewport_size(dict(width=width,height=height))
        keyboard(0)
        page.add_style_tag(content=f':root {{font-size:{percent}%}}')
        def bounds(selector):return page.locator(selector).first.bounding_box()
        def geometry():return [bounds('#result'),bounds('#keyboardPanel'),bounds('.candidateBar'),bounds('#keys .row:last-child')]
        def font_size(selector):return page.locator(selector).first.evaluate('e=>parseFloat(getComputedStyle(e).fontSize)')
        base=geometry()
        assert all(b for b in base)
        key_font=font_size('#keys .key')
        assert abs(key_font - 19*percent/100)<.05,(width,percent,key_font)
        assert 44<=base[3]['height']<=48.5,(width,percent,base)
        assert base[0]['height']>=30 and base[0]['y']+base[0]['height']<=base[1]['y'],(width,percent,base)
        assert base[3]['y']+base[3]['height']<=height and base[3]['width']<=width,(width,percent,base)
        for node_id in ['typeMode','handMode','kbMore','hideKeyboard']:
            ctl=bounds('#'+node_id)
            assert 0<=ctl['x'] and ctl['x']+ctl['width']<=width,(width,percent,node_id,ctl)
        assert not page.locator('#draftBar.has-text').count()
        assert not page.locator('#kbSettings').is_visible()
        assert page.locator('#draft').get_attribute('placeholder') is None
        assert 'maximum-scale' not in page.locator('meta[name="viewport"]').get_attribute('content')
        tag=f'{width}-{percent}'
        if (width,percent) in [(390,100),(320,100),(320,160),(320,200)]:snap(page,'compact-toolbar-'+tag)
        page.locator('#kbMore').click()
        assert geometry()==base and page.locator('#kbMore').get_attribute('aria-expanded')=='true'
        def check_picker():
            panel=page.locator('#kbMorePanel')
            assert panel.evaluate('e=>e.clientWidth>=e.scrollWidth && e.offsetTop+e.offsetHeight<=e.parentElement.clientHeight'),(width,percent)
            keys=panel.locator('.formChoice')
            assert keys.count()==3
            for key in keys.all():
                sample=key.locator('small').bounding_box()
                label=key.locator('span').bounding_box()
                assert sample['y']+sample['height']<=label['y']+1,(width,percent,sample,label)
        check_picker()
        if (width,percent) in [(390,100),(320,160)]:snap(page,'more-menu-'+tag)
        page.keyboard.press('Escape')
        assert not page.locator('#kbMorePanel').is_visible() and geometry()==base
        page.locator('#result').press('k')
        assert page.locator('#draft').input_value()=='k' and text()=='' and geometry()==base
        page.locator('#kbMore').click();check_picker();page.locator('#menuHan').click()
        assert page.locator('#draft').input_value()=='k' and geometry()==base
        page.locator('#handMode').click()
        assert page.locator('#canvas').is_visible() and bounds('#keyboardPanel')==base[1]
        page.locator('#typeMode').click()
        assert page.locator('#draft').input_value()=='k' and geometry()==base
        if width==320:
            page.locator('#draft').fill('marmardiexhxif')
            card=page.locator('#candidates .word[data-han-count="3"] strong').filter(has_text='麻麻爹').first
            assert card.evaluate('e=>e.scrollWidth<=e.clientWidth'),(percent,card.bounding_box())
            if percent in [100,160,200]:snap(page,'three-han-'+tag)
            if percent==200:
                page.locator('#draft').fill('ga')
                form=page.evaluate("wordById.get('w698').recommended")
                card=visible_form(form)
                assert card.locator('small').inner_text()=='上面'
                assert card.locator('strong').evaluate('e=>e.scrollWidth<=e.clientWidth')
                snap(page,'ga-merged-320-200')
                card.click();assert text()==form
            if percent in [160,200]:
                page.locator('#draft').fill('go')
                label=page.locator('#candidates .glossPair').first
                assert label.locator('.glossGap').evaluate("e=>getComputedStyle(e).display==='none'")
                assert label.evaluate("e=>e.getBoundingClientRect().width<=e.parentElement.clientWidth+1")
                if percent==200:snap(page,'two-meanings-320-200')
        page.locator('#hideKeyboard').click();assert not page.locator('#keyboardPanel').is_visible()
        page.locator('#result').click();page.wait_for_timeout(220);assert geometry()==base,(width,percent,base,geometry())
        metrics.append((width,percent,round(key_font,1),round(base[3]['height'],1),round(base[1]['height'],1)))
    page.set_viewport_size(dict(width=390,height=844))
    print('Viewport, root font %, letter px, touch row px, panel px:',metrics)
    print('One-tap predictions and ambiguous Han sound reset:',expected_sound(0),expected_sound(1))
    keyboard(0,True)
    page.locator('#draft').fill('bizika');choose('bízīkǎ')
    page.locator('#kbMore').click();page.locator('#menuHan').click()
    choose('萌','[data-source="sound"]')
    assert text()=='bízīkǎ萌'
    assert page.evaluate("editorContext().ids==='' && editorContext().sound===''")
    page.locator('#kbMore').click();page.locator('#menuMarked').click()
    page.locator('#draft').fill('gǎ');choose('gǎ')
    assert text()=='bízīkǎ萌gǎ'
    page.get_by_role('button',name='。',exact=True).click()
    assert page.evaluate("editorContext().ids.endsWith('<s>') && editorContext().sound==='<s>'")
    page.locator('#result').fill('改了正文')
    assert page.locator('#candidates [data-source="sound"]').count()==0
    keyboard(0,True)
    page.locator('#draft').fill('kam')
    tree=visible_form('kǎmǒng')
    assert tree.get_attribute('data-source')=='completion'
    assert tree.locator('small').inner_text()=='树';tree.click()
    assert text()=='kǎmǒng' and page.locator('#draft').input_value()==''
    keyboard(0)
    page.locator('#draft').fill('bizika');choose('bifzixkar')
    choose('mongr','[data-source="sound"]')
    assert text()=='bifzixkar mongr'
    keyboard(1)
    # Untoned typing yields one recommended Han per genuine meaning. Source
    # abbreviations and prefix matches with a differing tail are independent.
    for raw,meaning,canonical in [('go','他','过'),('gor','他','郭'),('gǒ','他','郭'),('tai','没有','太'),('ma','呢',None),('qiaga','弟兄',None)]:
        page.locator('#draft').fill(raw)
        picks=page.evaluate("([n,m])=>candidateItems.filter(c=>(c.members||[c]).some(i=>i.kind===0 && i.meaning===m && i.consumed===n)).map(c=>c.form)",[len(raw),meaning])
        assert len(picks)==1 and (canonical is None or picks[0]==canonical),(raw,meaning,picks)
    page.locator('#draft').fill('go')
    snap(page,'sense-go-untone')
    assert page.evaluate("new Set(candidateItems.map(i=>i.form)).size===candidateItems.length")
    multi=page.locator('#candidates button[aria-label="他、讨，过"]')
    assert multi.count()==1 and multi.locator('.glossPair span').all_text_contents()==['¹他',' ','²讨']
    multi.focus();page.keyboard.press('F2')
    all_meanings=page.locator('#readMeaning p').all_text_contents()
    assert all_meanings[:2]==['他','讨'] and '过错（借词本字）' in all_meanings,all_meanings
    page.locator('#readBack').click()
    multi.click();assert text()=='过' and page.evaluate("editorContext().ids===''")
    page.locator('#draft').fill('gor')
    snap(page,'sense-gor-tone')
    page.locator('#draft').fill('gogo')
    brother=page.locator('#candidates .word').filter(has=page.get_by_text('哥哥',exact=True)).first
    assert brother.locator('strong').inner_text()=='哥哥' and brother.locator('small').inner_text()==''
    page.locator('#draft').fill('akoa')
    assert page.evaluate("candidateItems.some(c=>c.form===wordById.get('ls60-21').recommended && c.meaning==='哥哥啊')")
    keyboard(1)
    page.locator('#draft').fill('ga')
    assert page.evaluate("candidateItems.some(c=>c.form==='架' && c.fullGloss.includes('吃、咬'))")
    same=page.locator('#candidates button[aria-label="上、干，夹"]')
    assert same.count()==1 and same.locator('.glossPair span').all_text_contents()==['¹上',' ','²干']
    same.click();assert text()=='夹' and page.evaluate("editorContext().ids===''")
    keyboard(1)
    page.locator('#draft').fill('ma')
    assert page.evaluate("candidateItems.some(c=>c.kind===1 && c.form!=='马') && !candidateItems.some(c=>c.kind===1 && c.form==='马' && c.consumed===2)")
    page.locator('#draft').fill('mav')
    horse=page.locator('#candidates [data-word-id="w1459"]').first
    assert horse.locator('strong').inner_text()=='马' and horse.locator('small').inner_text()==''
    before=text()
    box=horse.bounding_box();page.mouse.move(box['x']+box['width']/2,box['y']+box['height']/2)
    page.mouse.down();page.locator('#candidateReading').wait_for(state='visible',timeout=2500);page.mouse.up()
    assert page.locator('#candidateReading').is_visible() and not page.locator('#readMeaning').is_visible()
    page.locator('#readBack').click();assert text()==before and page.locator('#draft').input_value()=='mav'
    for raw in ['ga','jia','gaf','gá','jiá']:
        page.locator('#draft').fill(raw)
        frame=visible_form('架')
        assert frame.locator('small').text_content()==('' if raw.startswith('ji') else '吃'),raw
        snap(page,'matched-'+raw)
        frame.focus();page.keyboard.press('F2')
        if raw.startswith('ji'):
            assert not page.locator('#readMeaning').is_visible()
            snap(page,'reader-jia')
        else:assert '吃、咬' in page.locator('#readMeaning').inner_text()
        page.locator('#readChoose').click()
        assert text().endswith('架') and page.locator('#draft').input_value()==''
        if raw.startswith('ji'):assert page.evaluate("editorContext().ids==='' && editorContext().sound===''")
        else:assert page.evaluate("editorContext().ids.split(' ').at(-1)==='w684' && editorContext().sound==='gaf'")
    keyboard(1)
    for raw,form in [('jiazi','夹子'),('gazi','夹子'),('xie','鞋'),('hai','鞋'),
                     ('ga','稼'),('jia','稼'),('gá','稼'),('jiá','稼'),('gao','教'),('jiao','教'),('gang','降'),('jiang','降'),
                     ('zhuangga','庄稼'),('zhuangjia','庄稼')]:
        if raw=='zhuangga':keyboard(1)
        page.locator('#draft').fill(raw)
        common=visible_form(form)
        assert common.locator('small').text_content()=='',(raw,form)
        if raw=='zhuangga':snap(page,'jishou-zhuangjia')
        common.focus();page.keyboard.press('F2')
        assert not page.locator('#readMeaning').is_visible()
        page.locator('#readChoose').click();assert text().endswith(form) and not page.locator('#draft').input_value()
    keyboard(1)
    for raw in ['sanggo','gogo']:
        page.locator('#draft').fill(raw)
        loan=visible_form('山歌' if raw=='sanggo' else '哥哥')
        assert loan.locator('small').text_content()==''
        loan.focus();page.keyboard.press('F2')
        assert not page.locator('#readMeaning').is_visible()
        page.locator('#readBack').click()
    for raw in ['bangricai','banricai','bánrīcāi','banfrixcaix']:
        page.locator('#draft').fill(raw)
        noon=visible_form('半日猜')
        assert noon.locator('small').text_content()=='中午' and noon.get_attribute('data-consumed')==str(len(raw))
        if raw=='banricai':snap(page,'an-ang-noon')
        noon.click();assert page.locator('#draft').input_value()==''
    assert page.evaluate("core.matchesPrefix('áng','anf','án') && core.matchesPrefix('sān','sangx','sāng')")
    assert page.evaluate("""core.matchesPrefix('jiangga','jianfgax',"jián'gā")""")
    assert not page.evaluate("core.matchesPrefix('tangie','taxniex','tāniē')")
    page.locator('#showScene').click()
    meal=visible_form('牙叶架。');meal.click()
    assert text().endswith('牙叶架。') and not page.locator('#draft').input_value()
    page.locator('#draft').fill('banricaigaf')
    visible_form('半日猜').click();assert page.locator('#draft').input_value()=='gaf'
    visible_form('架').click()
    page.locator('#draft').fill('tangie')
    assert not page.evaluate("candidateItems.some(c=>(c.members||[c]).some(i=>i.id==='w2213' && i.consumed===6))")
    keyboard(1)
    page.locator('#draft').fill('sanggo');loan=page.get_by_role('button',name='山歌',exact=True).first
    assert loan.locator('small').inner_text()=='';loan.click()
    assert text()=='山歌'
    page.get_by_role('button',name='。',exact=True).click()
    assert page.evaluate("editorContext().ids.split(' ').slice(-2).join(' ')==='xu213-2-1 <s>'")
    page.locator('#draft').fill('boxlir');choose('波俐')
    assert text()=='山歌。波俐' # combination from independent user choices
    # The one visible glyph uses the full draft by default. Its reader keeps the
    # shorter operation available without guessing which possible meaning was intended.
    for keep_suffix in [False,True]:
        page.locator('#draft').fill('rif')
        card=page.locator('#candidates .word').filter(has=page.get_by_text('日',exact=True))
        for _ in range(50):
            if card.count():break
            assert page.locator('#nextCandidates').is_enabled()
            page.locator('#nextCandidates').click()
        assert card.count()==1 and card.get_attribute('data-consumed')=='3'
        if keep_suffix:
            card.focus();page.keyboard.press('F2')
            assert not page.locator('#readMeaning').is_visible()
            other=page.locator('#readEdits p').all_text_contents()
            assert any('做' in s and '多' in s for s in other),other
            page.get_by_role('button',name='用 ri，保留 f',exact=True).click()
        else:card.click()
        assert page.locator('#draft').input_value()==('f' if keep_suffix else '')
    page.locator('#draft').fill('marmardiexhxif');choose('麻麻爹')
    assert page.locator('#draft').input_value()=='hxif'
    choose('诶');assert text().endswith('麻麻爹诶')
    page.locator('#draft').fill('ban')
    assert page.locator('#candidates [data-source="completion"]').count()
    completion=page.locator('#candidates [data-source="completion"]').first
    val=completion.locator('strong').inner_text();before=text();completion.click()
    assert text()==before+val and page.locator('#draft').input_value()==''
    # A statistically proposed syllable with independently attested single sense.
    keyboard(0,True)
    confirm=page.locator('#candidates [data-source="sound"][aria-label="紧，jìn"]')
    for _ in range(50):
        if confirm.count():break
        assert page.locator('#nextCandidates').is_enabled()
        page.locator('#nextCandidates').click()
    assert confirm.locator('small').inner_text()=='紧';confirm.click()
    assert text()=='jìn'
    keyboard(2)
    # Real strokes, submitted by the recognition timer without pressing a shape button.
    pad=page.locator('#canvas');rect=pad.bounding_box()
    side=min(rect['width'],rect['height'])*.88
    for a,b,y in [(.23,.77,.2),(.29,.71,.48),(.1,.9,.8)]:
        px=lambda r:rect['x']+rect['width']/2+(r-.5)*side
        py=rect['y']+rect['height']/2+(y-.5)*side
        page.mouse.move(px(a),py);page.mouse.down();page.mouse.move(px(b),py,steps=5);page.mouse.up()
    page.wait_for_function("document.querySelector('#result').innerText==='三'",timeout=8000)
    # The demo glyph uses the same handwriting accept path. One lexical tap corrects its meaning/form.
    page.locator('#kbMore').click();page.locator('#menuSpell').click()
    page.locator('#draft').fill('尬')
    assert page.evaluate("['吃、咬','干、渴'].every(m=>candidateItems.some(c=>c.meaning===m))")
    choose('gaf')
    assert text().endswith('gaf')
    print('Confirmed one-sense label, automatic real handwriting, one-tap phonetic Han conversion: PASS')
    print('Ye, Han, prefix completions, shorter commit with editable suffix, independent recombination: PASS')
    with (root/'tools/story-demo/shange-spelling.tsv').open() as src:song=list(csv.DictReader(src,delimiter='\t'))
    for which,field in [('demoHan','han'),('demoTujia','ye'),('demoTone','diacritic')]:
        reset(True);page.locator('#'+which).click()
        page.wait_for_function("document.querySelector('#pause').textContent==='重播'",timeout=40000)
        after=''
        for e in song:
            if field!='han' and after and not after.endswith('\n'):after+=' '
            after+=e[field]+e['after'].replace('\\n','\n')
        assert text()==after,(which,text(),after)
    print('Four original song lines in Han, Ye and marked spelling: PASS')
    if args.all_han:
        keyboard(1)
        counts=page.evaluate("""()=>{
            const rows=tujiaCoreData.words.split('\\n').map(l=>l.split('\\t')).filter(r=>r[8]==='phrase'||r[8]==='tujia');
            const forms=new Set(rows.flatMap(r=>[r[1],r[10],r[11]]).filter(Boolean));
            let merged=0,alternateEdits=0;
            for(const raw of forms){
                change(raw);
                if(new Set(candidateItems.map(c=>c.form)).size!==candidateItems.length)
                    throw new Error('Duplicate Han card for '+raw);
                for(const c of candidateItems){
                    if(c.members?.length>1)merged++;
                    alternateEdits+=c.otherEdits?.length||0;
                }
            }
            return {rows:rows.length,forms:forms.size,merged,alternateEdits};
        }""")
        assert counts['rows']>=526 and counts['forms']>=1000,counts
        print('All dictionary spellings produce unique written cards:',counts)
        # Verify the shipped stroke data for the nine additional register-table characters.
        recognized=page.evaluate(r"""()=>{
            const templates=new Map(tujiaCoreData.pen.trim().split('\n').map(row=>row.split('\t')));
            return Array.from('侮凄坳懈桠稼筝躬隧',glyph=>{
                const strokes=templates.get(glyph).split(';').map(path=>path.split(' ').map(p=>p.split(',').map(Number)));
                return core.recognize(strokes)[0];
            }).join('');
        }""")
        assert recognized=='侮凄坳懈桠稼筝躬隧',recognized
        print('Nine additional characters are recognized from their registered strokes:',recognized)
    assert not errors,errors
