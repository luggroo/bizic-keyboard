// Build with the project's lexicon.cpp. Each process runs a single input length.
#include "lexicon.hpp"
#include <chrono>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <sys/resource.h>

static std::string readFile(const char *path) {
    std::ifstream file(path);
    if (!file) throw std::runtime_error(path);
    return std::string(std::istreambuf_iterator<char>(file), {});
}

static long peakKiB() {
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    return usage.ru_maxrss;
}

int main(int argc, char **argv) {
    if (argc != 6) return 2;
    int length = std::stoi(argv[4]);
    if (length < 1 || length > 1000000) return 2;
    std::string unit(argv[5]);
    if (unit.empty()) return 2;
    std::string draft;
    while (draft.size() < static_cast<size_t>(length)) draft += unit;
    draft.resize(length);
    tujia::Lexicon words(readFile(argv[1]), readFile(argv[2]), readFile(argv[3]));
    long baseline = peakKiB();
    auto start = std::chrono::steady_clock::now();
    auto found = words.lookup(draft, tujia::Mode::Han);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::cout << unit << ',' << length << ',' << found.size() << ',' << ms << ',' << baseline << ',' << peakKiB() << '\n';
}
