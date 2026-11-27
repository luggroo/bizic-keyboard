#!/usr/bin/env python3
"""Project the shared dictionaries into the built-in Android keyboard assets."""
import argparse
import re
import shutil
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / "shared/bundle/Assets"
ANDROID = ROOT / "app/src/main/assets"
HANDWRITING = ROOT / "app/src/handwriting/assets"
WORD_FILES = ("words.tsv", "northern-words.tsv")
TERMS = {
    "政治": "中国共产党 共产党 共產黨 国民党 國民黨 解放军 人民解放军 红军 紅軍 工农红军 中央军委 政府 乡政府 人民政府 政治 政权 政黨 政党 党中央 黨中央 党员 黨員 党支部 总书记 總書記 人民代表 全國人大 全国人大 毛泽东 毛澤東 习近平 習近平 蒋介石 天安门 天安門 六四事件 大跃进 大躍進 文革 文化大革命 法轮功 法輪功 台独 臺獨 藏独 疆独 分裂国家 反革命",
    "民族": "民族歧视 民族歧視 土蛮子 土蠻子 蛮家 蠻家 蛮夷 蠻夷 夷人",
    "历史": "改土归流 改土歸流 起义 起義 革命 北伐 抗日 抗战 抗戰 解放战争 八路军 八路軍 新四军 新四軍 长征 長征 封建 地主 奴隶 奴隸 殖民 皇帝 皇朝 王朝 清朝 明朝 元朝 宋朝 唐朝 汉朝 漢朝",
    "宗教": "宗教 佛教 佛寺 佛经 佛經 佛学 佛學 佛祖 菩萨 菩薩 拜佛 基督教 天主教 伊斯兰 伊斯蘭 穆斯林 上帝 耶稣 耶穌 道教 道士 儒教 寺庙 寺廟 庙宇 廟宇 和尚 尼姑 教堂 传教 傳教 信教 礼拜 禮拜 祭祀 祭祖 拜神 神灵 神靈 巫师 巫師 男巫 女巫 巫术 巫術 迷信 祈祷 祈禱 念经 念經",
    "性": "性交 性行为 性行為 性生活 性器官 生殖器 阴茎 陰莖 阴道 陰道 睾丸 睪丸 精液 外阴 外陰 乳房 裸体 裸體 裸露 性欲 性慾 遗精 遺精 同性恋 同性戀 手淫 自慰 肛交 强奸 強姦 强姦 强暴 強暴 性侵 性骚扰 性騷擾 卖淫 賣淫 嫖娼 妓女 淫秽 淫穢 色情 色情片 淫荡 淫蕩",
    "暴力": "杀人 殺人 杀害 殺害 杀死 殺死 杀戮 殺戮 谋杀 謀殺 屠杀 屠殺 枪杀 槍殺 枪毙 槍斃 砍头 砍頭 斩首 斬首 砍死 虐待 自杀 自殺 恐怖袭击 恐怖襲擊 战争 戰爭 武器 枪械 槍械 步枪 步槍 手枪 手槍 炸弹 炸彈 手榴弹 手榴彈 催泪弹 催淚彈 爆炸 殴打 毆打 枪击 槍擊 毒药 毒藥 行凶 行兇 血腥",
}
EXACT = {"宗教": {"佛", "拜", "鬼"}, "性": {"淫", "妓"}}
ENGLISH = {
    "政治": r"communist party|kuomintang|cultural revolution|government|political party",
    "民族": r"racial slur|ethnic slur",
    "历史": r"colonialism|slavery|emperor|civil war|revolution",
    "宗教": r"religion|buddhism|christianity|islam|muslim|shaman|missionary|worship",
    "性": r"sexual intercourse|sexual assault|homosexual(?:ity)?|rape|raped|prostitute|pornography|penis|vagina|semen|genital(?:s|ia)?|testicles?|nude|naked",
    "暴力": r"murder|suicide|massacre|behead(?:ed|ing)?|bomb|firearm|poison",
}
PATTERNS = {
    group: re.compile("|".join(re.escape(term) for term in sorted(terms.split(), key=lambda s: (-len(s), s)))
                      + r"|\b(?:" + ENGLISH[group] + r")\b", re.IGNORECASE)
    for group, terms in TERMS.items()
}


def is_tima(fields):
    """The documented 梯玛 forms also occur under 体马 and 体麻."""
    return bool(set(fields) & {"tixmax", "tivmar", "tʰi⁵⁵ma⁵⁵", "tʰi⁵¹ma²¹", "梯玛", "梯瑪", "体马", "体麻"})


def matches(fields):
    found = {}
    for field in fields:
        text = unicodedata.normalize("NFKC", field)
        for group, pattern in PATTERNS.items():
            hits = {m.group() for m in pattern.finditer(text)}
            hits.update(EXACT.get(group, set()).intersection(re.split(r"[\W_]+", text)))
            if hits:
                found.setdefault(group, set()).update(hits)
    return found


def project(shards):
    output = {}
    audit = []
    omitted_ids = set()
    retained_ids = set()
    for name, text in shards.items():
        kept = []
        for lineno, line in enumerate(text.splitlines(keepends=True), 1):
            fields = line.rstrip("\r\n").split("\t")
            if not line.strip() or line.startswith("#") or fields[0] in ("id", "syllable"):
                kept.append(line)
                continue
            if name in WORD_FILES:
                terms = matches(fields[3:7] + fields[9:10])
                exempt = is_tima(fields[1:2] + fields[3:5])
                if terms and not exempt:
                    omitted_ids.add(fields[0])
                else:
                    retained_ids.add(fields[0])
            else:
                terms = matches(fields[-1:] if name == "ipa-completions.tsv" else fields[1:2] + fields[3:4])
                exempt = is_tima(fields)
            if terms:
                audit.append((name, lineno, "保留梯玛" if exempt else "移除候选", terms, line.rstrip()))
            if not terms or exempt:
                kept.append(line)
        output[name] = "".join(kept).encode()
    return output, audit, omitted_ids - retained_ids


def asset_files(typing_only):
    paths = {p.relative_to(SHARED).as_posix(): p for p in SHARED.rglob("*") if p.is_file()}
    paths.update({p.relative_to(ANDROID).as_posix(): p for p in ANDROID.rglob("*") if p.is_file()})
    if typing_only:
        paths = {name: path for name, path in paths.items()
                 if not name.startswith(("handwriting/", "ismantic/"))
                 and name not in ("handwriting-readings.tsv", "handwriting-tones.tsv")}
    else:
        paths.update({p.relative_to(HANDWRITING).as_posix(): p
                      for p in HANDWRITING.rglob("*") if p.is_file()})
    projected, audit, removed_ids = project({name: paths[name].read_text()
                                             for name in (*WORD_FILES, "ipa-completions.tsv", "syllable_senses.tsv")})
    ngrams = paths["ngrams.tsv"].read_text().splitlines(keepends=True)
    projected["ngrams.tsv"] = "".join(
        line for line in ngrams if not removed_ids.intersection(
            line.rstrip("\r\n").split("\t", 2)[0].split() + line.rstrip("\r\n").split("\t", 2)[1:2])
    ).encode()
    return {name: projected[name] if name in projected else path.read_bytes()
            for name, path in paths.items()}, audit


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--typing-only", action="store_true")
    args = parser.parse_args()
    files, _ = asset_files(args.typing_only)
    if args.output.exists():
        shutil.rmtree(args.output)
    for name, data in files.items():
        target = args.output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
