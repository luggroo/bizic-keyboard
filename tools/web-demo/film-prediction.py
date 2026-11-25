#!/usr/bin/env python3
"""Capture real browser key presses and candidate clicks in the offline demo."""
import argparse
import re
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile
from playwright.sync_api import sync_playwright

ap=argparse.ArgumentParser()
ap.add_argument('bundle',type=Path)
ap.add_argument('movie',type=Path)
ap.add_argument('--chromium',default='/usr/bin/chromium')
ap.add_argument('--readings',action='store_true',help='Film ga/jia/gaf, an/ang and Chinese original-form lookup')
args=ap.parse_args()
with ZipFile(args.bundle) as z:html=z.read('index.html').decode()
args.movie.parent.mkdir(parents=True,exist_ok=True)
with TemporaryDirectory() as temp,sync_playwright() as pw:
    work=Path(temp);frames=[]
    browser=pw.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--disable-dev-shm-usage'])
    page=browser.new_page(viewport={'width':390,'height':844},offline=True,locale='zh-CN')
    page.set_content(html)
    page.locator('#take').click();page.locator('#result').click()
    page.locator('#kbMore').click();page.locator('#menuMarked').click()
    def shot(seconds):
        p=work/f'{len(frames):03}.png';page.screenshot(path=p);frames.append((p,seconds))
    def press(el,seconds=.33):
        el.scroll_into_view_if_needed();box=el.bounding_box();assert box
        page.mouse.move(box['x']+box['width']/2,box['y']+box['height']/2)
        page.mouse.down();shot(seconds);page.mouse.up()
    def type_raw(raw):
        for key in raw:
            press(page.get_by_role('button',name=key.upper(),exact=True));shot(.24)
    def find_card(name,kind,gloss=None):
        cards=page.locator('#candidates button[data-source="'+kind+'"]').filter(has=page.locator('strong').filter(has_text=re.compile('^'+re.escape(name)+'$')))
        if gloss:cards=cards.filter(has=page.locator('small').filter(has_text=gloss))
        card=cards.first
        for _ in range(40):
            if card.count():break
            more=page.locator('#nextCandidates');assert more.is_enabled(),name
            press(more,.25);shot(.4)
        assert card.count(),name
        return card
    def choose(name,kind,gloss=None):
        card=find_card(name,kind,gloss)
        shot(1.6);press(card,.4);shot(1.4)
    def body():return page.locator('#result').inner_text()
    def newline():page.locator('#draft').press('Enter')
    shot(1)
    if args.readings:
        press(page.locator('#kbMore'));shot(.5);press(page.locator('#menuHan'));shot(.7)
        examples=[('ga','架','吃'),('jia','架',''),('gaf','架','吃'),('banricai','半日猜','中午'),('sanggo','山歌',''),('gazi','夹子',''),('hai','鞋',''),
                  ('ga','稼',''),('zhuangga','庄稼','')]
        for i,(raw,form,meaning) in enumerate(examples):
            if i:newline();shot(.4)
            type_raw(raw)
            card=find_card(form,'exact')
            assert card.locator('small').inner_text()==meaning,(raw,form)
            shot(1.5)
            if form=='架' and raw in ['ga','jia'] or raw=='sanggo':
                rect=card.bounding_box();page.mouse.move(rect['x']+rect['width']/2,rect['y']+rect['height']/2)
                page.mouse.down();page.locator('#candidateReading').wait_for(state='visible');page.mouse.up()
                assert page.locator('#readMeaning').is_visible()==bool(meaning)
                shot(1.5);press(page.locator('#readBack'));shot(.6)
            press(card,.4);shot(1.2)
            assert body().splitlines()[-1]==form
        assert body().splitlines()==[e[1] for e in examples]
    else:
        type_raw('kam');choose('kǎmǒng','completion')
        assert body().strip()=='kǎmǒng'
        newline();type_raw('bizika');choose('bízīkǎ','exact')
        choose('mǒng','sound');choose('gǎ','sound')
        assert body().splitlines()[-1]=='bízīkǎ mǒng gǎ'
        newline();press(page.locator('#kbMore'));shot(.6);press(page.locator('#menuHan'));shot(.6)
        type_raw('bizika');choose('币子咔','exact');choose('萌','sound');type_raw('ga');choose('夹','exact')
        assert body().splitlines()[-1]=='币子咔萌夹'
        newline();type_raw('go');choose('过','exact','他')
        newline();type_raw('gor');choose('郭','exact','他')
        assert body().splitlines()[-2:]==['过','郭']
    shot(2.1);browser.close()
    seq=work/'frames.txt'
    seq.write_text("ffconcat version 1.0\n"+''.join(f"file '{p}'\nduration {s}\n" for p,s in frames)+f"file '{frames[-1][0]}'\n")
    subprocess.run(['ffmpeg','-y','-v','error','-f','concat','-safe','0','-i',seq,'-vf','fps=30','-c:v','libx264','-threads','3','-crf','17','-preset','veryfast','-pix_fmt','yuv420p','-movflags','+faststart',args.movie],check=True)
print(args.movie)
