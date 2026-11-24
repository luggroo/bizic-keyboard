#include "../src/strokes.hpp"

#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <sstream>
#include <stdexcept>

namespace {
using Paths = std::vector<std::vector<float>>;

void expect(bool passed, const char* message) {
    if (!passed) throw std::runtime_error(message);
}

// These independently drawn paths were also passed to the original Android matcher.
// Capture its full 12-item ranking to guard against drift between releases.
void expectRanking(const tujia::Strokes& model, const Paths& paths, const char* expected) {
    std::istringstream stream(expected);
    const std::vector<std::string> ranking{std::istream_iterator<std::string>(stream), {}};
    const auto actual = model.recognize(paths, 12);
    if (ranking != actual) {
        std::ostringstream failure;
        failure << "Expected " << expected << ", got";
        for (const auto& glyph : actual) failure << ' ' << glyph;
        throw std::runtime_error(failure.str());
    }
    expect(model.recognize(paths, 1) == std::vector<std::string>{ranking.front()}, "Limit ignored");
}
}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "Pass the path to medians.tsv\n";
        return 2;
    }
    std::ifstream input(argv[1]);
    const std::string tsv{std::istreambuf_iterator<char>(input), {}};
    const tujia::Strokes model(tsv);
    expect(model.size() == 3561, "Incomplete handwriting vocabulary");
    expect(model.recognize({}, 12).empty(), "Empty pen input");
    expect(model.recognize({{5,5}}, 12).empty(), "Single tap");
    expect(model.recognize({{5,5,6}}, 12).empty(), "Odd coordinate count");
    expect(model.recognize({{1,std::numeric_limits<float>::infinity()}}, 12).empty(),
           "Infinite coordinate");
    expect(model.recognize({{1,2,30,20}}, 0).empty(), "Zero limit");
    expect(model.recognize({{20,52,37,51,60,50,82,49}}, 1) == std::vector<std::string>{"一"},
           "Common Mandarin-only handwriting");
    expectRanking(model, {{15,40,45,39,85,39},{48,8,46,90}},
                  "十 千 干 七 丁 于 寸 乜 士 巾 力 子");
    expectRanking(model, {{32,19,70,17},{27,48,75,47},{10,84,89,83}},
                  "三 亡 上 土 卫 士 亏 已 己 之 马 弓");
    expectRanking(model, {{20,39,80,39},{49,10,49,93},{45,44,32,63,13,81},{54,47,69,64,87,82}},
                  "木 水 爪 内 厄 书 凤 友 屯 术 尤 巿");
    expectRanking(model, {{10,20,10,64},{11,19,36,19,35,64},{10,64,36,64},
                          {42,39,91,37},{67,9,66,92}},
                  "叶 甘 帅 汁 打 扑 叮 册 冉 甩 师 母");
    expectRanking(model, {{8,22,52,21},{30,7,29,37},{13,34,13,54},{13,34,49,34,49,54},
                          {13,54,49,54},{23,55,19,71,5,88},{36,55,36,83,39,89,83,89,91,86,92,79},
                          {52,31,94,30},{78,9,78,75,71,72},{57,47,66,59}},
                  "尅 赶 匪 埋 浦 框 耕 埔 脏 桂 配 逝");
    bool rejected = false;
    try {
        const tujia::Strokes malformed("甲\t3,4;x,6\n");
    } catch (const std::invalid_argument&) {
        rejected = true;
    }
    expect(rejected, "Accepted malformed glyph data");
    std::cout << model.size() << " glyphs, five 12-candidate rankings matched.\n";
}
