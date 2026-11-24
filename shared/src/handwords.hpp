#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace tujia {

// A hit ends at the caret. A continuation has one additional Han character
// to append. The number consumed refers to the writer's actual input.
struct HandHit {
    size_t id, length;
    unsigned rank;
    uint64_t frequency;
    char32_t next;
};

class HandwordIndex {
public:
    static constexpr size_t contextCharacters = 4;
    using Neighbours = std::array<std::u32string, 3>;
    std::unordered_map<char32_t, Neighbours> readings;
    size_t longest = 0;

    void add(size_t id, uint64_t frequency, const std::u32string& canonical,
             const std::vector<std::u32string>& units, const std::vector<std::u32string>& variants,
             std::vector<std::u32string> aliases) {
        if (canonical.empty()) return;
        Word word{id, frequency, canonical, units, variants, std::move(aliases)};
        if (word.units.empty()) for (auto c : canonical) word.units.emplace_back(1, c);
        const size_t index = words.size();
        auto addFirst = [&](char32_t c) {
            auto& ids = starts[c];
            if (ids.empty() || ids.back() != index) ids.push_back(index);
        };
        auto addRegional = [&](char32_t c) {
            addFirst(c);
            auto it = readings.find(c);
            if (it != readings.end()) for (const auto& group : it->second)
                for (char32_t peer : group) addFirst(peer);
        };
        addRegional(canonical.front());
        longest = std::max(longest, canonical.size());
        if (!variants.empty()) for (char32_t c : variants.front()) addFirst(c);
        for (const auto& alias : word.aliases) {
            if (alias.empty()) continue;
            addRegional(alias.front());
            longest = std::max(longest, alias.size());
        }
        words.push_back(std::move(word));
    }

    std::vector<HandHit> lookup(std::u32string_view han, bool continuations) const {
        std::vector<HandHit> hits;
        const size_t maximum = std::min({han.size(), longest, contextCharacters});
        for (size_t length = maximum; length > 0; --length) {
            const auto raw = han.substr(han.size() - length);
            auto first = starts.find(raw.front());
            if (first == starts.end()) continue;
            for (size_t index : first->second) {
                const Word& word = words[index];
                const size_t beginning = hits.size();
                auto emit = [&](unsigned rank, char32_t next) {
                    if (continuations != (next != 0)) return;
                    for (size_t i = beginning; i < hits.size(); ++i) {
                        if (hits[i].next != next) continue;
                        hits[i].rank = std::min(hits[i].rank, rank);
                        return;
                    }
                    hits.push_back({word.id, length, rank, word.frequency, next});
                };
                match(word, raw, emit);
            }
        }
        std::stable_sort(hits.begin(), hits.end(), [](const HandHit& a, const HandHit& b) {
            if (a.length != b.length) return a.length > b.length;
            if (a.rank != b.rank) return a.rank < b.rank;
            return a.frequency > b.frequency;
        });
        return hits;
    }

private:
    struct Word {
        size_t id;
        uint64_t frequency;
        std::u32string canonical;
        std::vector<std::u32string> units, variants, aliases;
    };
    std::vector<Word> words;
    std::unordered_map<char32_t, std::vector<size_t>> starts;
    static constexpr unsigned absent = 6;

    unsigned distance(char32_t written, char32_t canonical) const {
        if (written == canonical) return 0;
        auto it = readings.find(canonical);
        if (it != readings.end()) for (size_t i = 0; i < it->second.size(); ++i) {
            if (it->second[i].find(written) != std::u32string::npos) return 2 + i;
        }
        return absent;
    }

    template<class Emit>
    void match(const Word& word, std::u32string_view raw, Emit emit) const {
        // A recorded one-character variant may stand for a whole two-Han syllable.
        // Keep the best route to each boundary, without expanding variant products.
        using Position = std::pair<size_t, unsigned>;
        std::vector<Position> positions{{0, 0}}, following;
        for (size_t i = 0; i < word.units.size() && !positions.empty(); ++i) {
            const auto& unit = word.units[i];
            following.clear();
            auto advance = [&](size_t end, unsigned rank) {
                for (auto& p : following) if (p.first == end) {
                    p.second = std::min(p.second, rank);
                    return;
                }
                following.emplace_back(end, rank);
            };
            for (auto [pos, rank] : positions) {
                if (pos == raw.size()) { emit(rank, unit.front()); continue; }
                if (i < word.variants.size() && word.variants[i].find(raw[pos]) != std::u32string::npos)
                    advance(pos + 1, std::max(rank, 5u));
                size_t k = 0;
                while (k < unit.size() && pos < raw.size()) {
                    rank = std::max(rank, distance(raw[pos++], unit[k++]));
                    if (rank == absent) break;
                }
                if (rank == absent) continue;
                if (k == unit.size()) advance(pos, rank);
                else emit(rank, unit[k]);
            }
            positions.swap(following);
        }
        for (auto [pos, rank] : positions) if (pos == raw.size()) emit(rank, 0);
        for (const auto& alias : word.aliases) {
            if (raw.size() > alias.size()) continue;
            unsigned rank = 1;
            for (size_t i = 0; i < raw.size() && rank < absent; ++i)
                rank = std::max(rank, distance(raw[i], alias[i]));
            if (rank == absent) continue;
            if (alias.size() == raw.size()) emit(rank, 0);
            else emit(rank, alias.size() == word.canonical.size() ? word.canonical[raw.size()] : alias[raw.size()]);
        }
    }
};
} // namespace tujia
