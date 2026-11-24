#!/usr/bin/env python3
"""Exercise the downloadable HTML with a real browser, with all network requests blocked."""
import argparse
import csv
from pathlib import Path
from zipfile import ZipFile
from playwright.sync_api import sync_playwright

ap=argparse.ArgumentParser()
ap.add_argument('bundle',type=Path)
ap.add_argument('--chromium',default='/usr/bin/chromium')
ap.add_argument('--frames',type=Path)
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
        if mode==2:page.locator('#handMode').click()
        else:
            page.locator('#typeMode').click()
            page.locator('#kbMore').click()
            page.locator('#menuHan' if mode else '#menuSpell').click()
        if marked:
            page.locator('#kbMore').click();page.locator('#kbSettings').click()
            page.locator('#orthography').select_option('diacritic')
            page.locator('#closeSettings').click()
    def text():return page.locator('#result').evaluate('e=>e.value')
    def choose(text,attr=''):
        card=page.locator('#candidates button'+attr).filter(has=page.get_by_text(text,exact=True)).first
        card.click()
    def expected_sound(output_mode):
        keyboard(output_mode,True)
        page.locator('#draft').fill('bizika')
        choose('币兹咔' if output_mode else 'bízīkǎ','[data-source="exact"]')
        snap(page,'after-word')
        card=page.locator('#candidates [data-source="sound"]').first
        form=card.locator('strong').inner_text();assert form==('萌' if output_mode else 'mǒng')
        assert card.locator('small').inner_text()==''
        before=text();card.click()
        assert text()==before+('' if output_mode else ' ')+form
        assert page.locator('#draft').input_value()==''
        snap(page,'after-first-prediction')
        next_card=page.locator('#candidates [data-source="sound"]').first
        next_form=next_card.locator('strong').inner_text()
        assert next_form
        before=text();next_card.click()
        assert text()==before+('' if output_mode else ' ')+next_form
        assert page.locator('#draft').input_value()==''
        snap(page,'after-second-prediction')
        return form,next_form
    # The outer editor, candidate strip, and bottom key stay fixed while switching panels.
    for width,height,font_scale in [(390,844,1),(320,568,1.25)]:
        page.set_viewport_size(dict(width=width,height=height))
        keyboard(0)
        if font_scale!=1:
            page.add_style_tag(content=f'.kbhead button,#kbMorePanel button,#hideKeyboard,#kbMore {{font-size:{16*font_scale}px}} .word strong,.key {{font-size:{22*font_scale}px}}')
        def bounds(selector):return page.locator(selector).first.bounding_box()
        def geometry():return [bounds('#result'),bounds('#keyboardPanel'),bounds('.candidateBar'),bounds('#keys .row:last-child')]
        base=geometry()
        assert all(b for b in base)
        assert 48<=base[3]['height']<=58,(width,base)
        assert base[0]['height']>=32 and base[0]['y']+base[0]['height']<=base[1]['y'],(width,base)
        assert base[3]['y']+base[3]['height']<=height and base[3]['width']<=width,(width,base)
        assert not page.locator('#draftBar.has-text').count()
        assert not page.locator('#kbSettings').is_visible()
        assert page.locator('#draft').get_attribute('placeholder') is None
        snap(page,'compact-toolbar-'+str(width))
        page.locator('#kbMore').click()
        assert geometry()==base and page.locator('#kbMore').get_attribute('aria-expanded')=='true'
        assert page.locator('#kbMorePanel').evaluate('e=>e.clientWidth>=e.scrollWidth && e.offsetTop+e.offsetHeight<=e.parentElement.clientHeight')
        snap(page,'more-menu-'+str(width))
        page.keyboard.press('Escape')
        assert not page.locator('#kbMorePanel').is_visible() and geometry()==base
        page.locator('#result').press('k')
        assert page.locator('#draft').input_value()=='k' and text()=='' and geometry()==base
        page.locator('#kbMore').click();page.locator('#menuHan').click()
        assert page.locator('#draft').input_value()=='k' and geometry()==base
        page.locator('#handMode').click()
        assert page.locator('#canvas').is_visible() and bounds('#keyboardPanel')==base[1]
        page.locator('#typeMode').click()
        assert page.locator('#draft').input_value()=='k' and geometry()==base
        if width==320:
            page.locator('#draft').fill('marmardiexhxif')
            card=page.locator('#candidates .word[data-han-count="3"] strong').filter(has_text='麻麻爹').first
            assert card.evaluate('e=>e.scrollWidth<=e.clientWidth'),card.bounding_box()
            snap(page,'readable-three-han-320')
        page.locator('#hideKeyboard').click();assert not page.locator('#keyboardPanel').is_visible()
        page.locator('#result').click();page.wait_for_timeout(220);assert geometry()==base,(width,base,geometry())
    page.set_viewport_size(dict(width=390,height=844))
    print('390px / 320px + large text: four rows of 48–58px; stable menu, handwriting and editor: PASS')
    print('Two consecutive one-tap script-independent predictions:',expected_sound(0),expected_sound(1))
    keyboard(0,True)
    page.locator('#draft').fill('bizika');choose('bízīkǎ')
    page.locator('#kbMore').click();page.locator('#menuHan').click()
    choose('萌','[data-source="sound"]')
    assert text()=='bízīkǎ萌'
    page.locator('#kbMore').click();page.locator('#menuSpell').click()
    choose('gǎ','[data-source="sound"]')
    assert text()=='bízīkǎ萌gǎ'
    page.get_by_role('button',name='。',exact=True).click()
    assert page.evaluate("editorContext().ids.endsWith('<s>') && editorContext().sound==='<s>'")
    page.locator('#result').fill('改了正文')
    assert page.locator('#candidates [data-source="sound"]').count()==0
    keyboard(0,True)
    page.locator('#draft').fill('karm')
    tree=page.locator('#candidates button[data-source="completion"]').filter(has=page.get_by_text('kǎmǒng',exact=True)).first
    assert tree.locator('small').inner_text()=='树';tree.click()
    assert text()=='kǎmǒng' and page.locator('#draft').input_value()==''
    keyboard(0)
    page.locator('#draft').fill('bizika');choose('bifzixkar')
    choose('mongr','[data-source="sound"]')
    assert text()=='bifzixkar mongr'
    keyboard(1)
    page.locator('#draft').fill('sanggo');loan=page.get_by_role('button',name='山歌',exact=True).first
    assert loan.locator('small').inner_text()=='';loan.click()
    assert text()=='山歌'
    page.get_by_role('button',name='。',exact=True).click()
    assert page.evaluate("editorContext().ids.split(' ').slice(-2).join(' ')==='xu213-2-1 <s>'")
    page.locator('#draft').fill('boxlir');choose('波俐')
    assert text()=='山歌。波俐' # combination from independent user choices
    # Two 日 choices have different edit operations. Reaching the same glyph
    # does not make ri (retain f) interchangeable with rif (consume all three).
    for label,consumed,suffix in [('做，日','2','f'),('日','3','')]:
        page.locator('#draft').fill('rif')
        match=page.locator('#candidates [aria-label="'+label+'"][data-consumed="'+consumed+'"]')
        for _ in range(50):
            if match.count():break
            assert page.locator('#nextCandidates').is_enabled()
            page.locator('#nextCandidates').click()
        match.first.click();assert page.locator('#draft').input_value()==suffix
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
    assert not errors,errors
