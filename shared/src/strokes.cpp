#include "strokes.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <locale>
#include <sstream>
#include <stdexcept>
#include <utility>

namespace tujia {
namespace {

constexpr int grid = 24;
constexpr int samples = 7;
constexpr int nearest = 48;
constexpr double pi = 3.14159265358979323846;
using Path = std::vector<float>;
using Paths = std::vector<Path>;
using SampledPath = std::array<float, 2 * samples>;
using DistanceMap = std::array<std::uint8_t, grid * grid>;

bool validPath(const Path& path) {
    return !path.empty() && path.size() % 2 == 0 &&
           std::all_of(path.begin(), path.end(), [](float value) { return std::isfinite(value); });
}

// Measure at equal arc-length intervals so finger speed does not affect the match.
SampledPath resample(const Path& pts, const std::vector<float>& lengths) {
    SampledPath result{};
    const auto last = lengths.size() - 1;
    std::size_t segment = 0;
    for (int i = 0; i < samples; ++i) {
        const float target = lengths[last] * i / (samples - 1);
        while (segment + 1 < last && lengths[segment + 1] < target) ++segment;
        const auto end = std::min(last, segment + 1);
        const float delta = lengths[end] - lengths[segment];
        const float fraction = delta == 0 ? 0 : (target - lengths[segment]) / delta;
        for (int axis = 0; axis < 2; ++axis) {
            const float start = pts[2 * segment + axis];
            result[2 * i + axis] = start + fraction * (pts[2 * end + axis] - start);
        }
    }
    return result;
}

struct Shape {
    DistanceMap distances;
    std::vector<std::uint16_t> ink;
    std::vector<SampledPath> strokes;
    std::array<float, 4> directions{};

    explicit Shape(const Paths& raw) {
        float minX = std::numeric_limits<float>::max(), minY = minX;
        float maxX = -minX, maxY = -minY;
        for (const auto& path : raw) {
            for (std::size_t i = 0; i < path.size(); i += 2) {
                minX = std::min(minX, path[i]);   maxX = std::max(maxX, path[i]);
                minY = std::min(minY, path[i+1]); maxY = std::max(maxY, path[i+1]);
            }
        }
        const float width = maxX - minX, height = maxY - minY;
        if (!std::isfinite(width) || !std::isfinite(height)) {
            throw std::invalid_argument("Stroke bounds exceed the supported range");
        }
        const float extent = std::max({1.f, width, height});
        const float scaleX = (grid - 3) / std::max(1.f, .72f * extent + .28f * width);
        const float scaleY = (grid - 3) / std::max(1.f, .72f * extent + .28f * height);
        const float shiftX = (grid - 1 - width * scaleX) / 2;
        const float shiftY = (grid - 1 - height * scaleY) / 2;
        distances.fill(100);
        float totalLength = 0;
        for (const auto& src : raw) {
            Path pts(src.size());
            std::vector<float> cumulative(src.size() / 2);
            for (std::size_t i = 0; i < src.size(); i += 2) {
                const float x = (src[i] - minX) * scaleX + shiftX;
                const float y = (src[i+1] - minY) * scaleY + shiftY;
                pts[i] = x;
                pts[i+1] = y;
                if (i == 0) {
                    mark(x, y);
                    continue;
                }
                const float dx = x - pts[i-2], dy = y - pts[i-1];
                const float length = static_cast<float>(std::hypot(double(dx), double(dy)));
                cumulative[i / 2] = cumulative[i / 2 - 1] + length;
                double angle = std::atan2(double(dy), double(dx));
                if (angle < 0) angle += pi;
                const double bin = 4 * angle / pi;
                const int axis = static_cast<int>(bin);
                const float blend = static_cast<float>(bin - axis);
                directions[axis % 4] += length * (1 - blend);
                directions[(axis + 1) % 4] += length * blend;
                totalLength += length;
                const int steps = std::max(1, static_cast<int>(std::ceil(length * 2)));
                for (int j = 1; j <= steps; ++j) {
                    mark(pts[i-2] + dx*j/steps, pts[i-1] + dy*j/steps);
                }
            }
            strokes.push_back(resample(pts, cumulative));
        }
        if (totalLength > 0) {
            for (float& direction : directions) direction /= totalLength;
        }
        for (int i = 0; i < grid * grid; ++i) {
            if (distances[i] == 0) ink.push_back(static_cast<std::uint16_t>(i));
        }
        // Two chamfer sweeps. A cardinal step costs 3, a diagonal step costs 4.
        for (int y = 0; y < grid; ++y) {
            for (int x = 0; x < grid; ++x) {
                const int p = y * grid + x;
                if (x > 0) relax(p, p - 1, 3);
                if (y == 0) continue;
                relax(p, p - grid, 3);
                if (x > 0) relax(p, p - grid - 1, 4);
                if (x + 1 < grid) relax(p, p - grid + 1, 4);
            }
        }
        for (int y = grid - 1; y >= 0; --y) {
            for (int x = grid - 1; x >= 0; --x) {
                const int p = y * grid + x;
                if (x + 1 < grid) relax(p, p + 1, 3);
                if (y + 1 >= grid) continue;
                relax(p, p + grid, 3);
                if (x > 0) relax(p, p + grid - 1, 4);
                if (x + 1 < grid) relax(p, p + grid + 1, 4);
            }
        }
    }

private:
    void mark(float x, float y) {
        const auto col = static_cast<int>(std::floor(x + .5f));
        const auto row = static_cast<int>(std::floor(y + .5f));
        distances[row * grid + col] = 0;
    }

    void relax(int index, int from, int cost) {
        distances[index] = static_cast<std::uint8_t>(
            std::min(int(distances[index]), distances[from] + cost));
    }
};

struct Glyph {
    std::string text;
    Shape shape;
};

struct Match {
    const Glyph* glyph;
    float score;
};

float meanDistance(const std::vector<std::uint16_t>& ink, const DistanceMap& distances) {
    int sum = 0;
    for (auto point : ink) sum += distances[point];
    return static_cast<float>(sum) / ink.size();
}

float geometryScore(const Shape& drawn, const Shape& ref, int difference) {
    const float distance = (meanDistance(drawn.ink, ref.distances)
                            + meanDistance(ref.ink, drawn.distances)) / 6.f;
    float axes = 0;
    for (std::size_t i = 0; i < drawn.directions.size(); ++i)
        axes += std::abs(drawn.directions[i] - ref.directions[i]);
    return distance + 1.65f * axes + difference * (drawn.strokes.size() < 5 ? 1.2f : .65f);
}

float orderPenalty(const Shape& drawn, const Shape& ref) {
    if (drawn.strokes.size() != ref.strokes.size()) return .85f;
    float distance = 0;
    for (std::size_t i = 0; i < drawn.strokes.size(); ++i) {
        const auto& a = drawn.strokes[i];
        const auto& b = ref.strokes[i];
        for (std::size_t j = 0; j < a.size(); j += 2)
            distance += static_cast<float>(std::hypot(double(a[j] - b[j]), double(a[j+1] - b[j+1])));
    }
    return .22f * std::min(5.f, distance / (samples * drawn.strokes.size()));
}

Paths parsePaths(std::string line) {
    std::replace(line.begin(), line.end(), ',', ' ');
    std::istringstream strokeStream(std::move(line));
    Paths result;
    std::string stroke;
    while (std::getline(strokeStream, stroke, ';')) {
        std::istringstream numbers(std::move(stroke));
        numbers.imbue(std::locale::classic());
        Path points;
        while (!(numbers >> std::ws).eof()) {
            float coordinate;
            if (!(numbers >> coordinate)) throw std::invalid_argument("Invalid template coordinate");
            points.push_back(coordinate);
        }
        if (!validPath(points)) throw std::invalid_argument("Invalid stroke template");
        result.push_back(std::move(points));
    }
    if (result.empty()) throw std::invalid_argument("Missing stroke template");
    return result;
}

}  // namespace

struct Strokes::Model {
    std::vector<Glyph> glyphs;

    explicit Model(const std::string& tsv) {
        std::istringstream rows(tsv);
        std::string line;
        while (std::getline(rows, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty() || line.front() == '#') continue;
            const auto tab = line.find('\t');
            if (tab == std::string::npos || tab == 0) throw std::invalid_argument("Missing handwriting character");
            glyphs.push_back({line.substr(0, tab), Shape(parsePaths(line.substr(tab + 1)))});
        }
        if (glyphs.empty()) throw std::invalid_argument("No handwriting templates");
    }
};

Strokes::Strokes(const std::string& tsv) : model_(std::make_shared<Model>(tsv)) {}

int Strokes::size() const { return static_cast<int>(model_->glyphs.size()); }

std::vector<std::string> Strokes::recognize(const Paths& input, int limit) const {
    if (limit <= 0 || input.empty() || !std::all_of(input.begin(), input.end(), validPath)) return {};
    try {
        const Shape drawn(input);
        if (drawn.ink.size() < 2) return {};
        std::vector<Match> matches;
        matches.reserve(model_->glyphs.size());
        const int count = static_cast<int>(input.size());
        for (const auto& glyph : model_->glyphs) {
            const int difference = std::abs(static_cast<int>(glyph.shape.strokes.size()) - count);
            if (difference <= std::max(3, count / 2))
                matches.push_back({&glyph, geometryScore(drawn, glyph.shape, difference)});
        }
        const auto byScore = [](const Match& a, const Match& b) { return a.score < b.score; };
        std::stable_sort(matches.begin(), matches.end(), byScore);
        if (matches.size() > nearest) matches.resize(nearest);
        for (auto& match : matches) match.score += orderPenalty(drawn, match.glyph->shape);
        std::stable_sort(matches.begin(), matches.end(), byScore);
        std::vector<std::string> result;
        const auto countToReturn = std::min(matches.size(), static_cast<std::size_t>(limit));
        result.reserve(countToReturn);
        for (std::size_t i = 0; i < countToReturn; ++i) result.push_back(matches[i].glyph->text);
        return result;
    } catch (const std::invalid_argument&) {
        return {};
    }
}

}  // namespace tujia
