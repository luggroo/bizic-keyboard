// Resolve each recorded word against the same dictionary and C++ lookup used by the IME.
#include "lexicon.hpp"
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

int main(int argc, char** argv) {
    if (argc!=3) return 2;
    std::ifstream words(argv[1]), script(argv[2]);
    if (!words || !script) return 2;
    std::string tsv((std::istreambuf_iterator<char>(words)),{});
    tujia::Lexicon model(tsv);
    std::string line;
    size_t number=0;
    while (std::getline(script,line)) {
        if (line.empty() || line[0]=='#' || line.rfind("keys\t",0)==0) continue;
        std::istringstream row(line);
        std::string raw,text,meaning;
        std::getline(row,raw,'\t'); std::getline(row,text,'\t'); std::getline(row,meaning,'\t');
        ++number;
        const auto choices=model.lookup(raw,tujia::Mode::Han);
        size_t i=0;
        for (;i<choices.size();++i) {
            const auto& c=choices[i];
            if (c.text==text && c.meaning==meaning && c.consumed==static_cast<int>(raw.size())) break;
        }
        if (i==choices.size()) {
            std::cerr << "Missing complete candidate on row " << number << ": " << raw << " " << text << "\n";
            return 1;
        }
        std::cout << (i+1) << "\n";
    }
}
