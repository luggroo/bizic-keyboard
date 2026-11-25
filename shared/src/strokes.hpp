#pragma once

#include <memory>
#include <string>
#include <vector>

namespace tujia {

// Offline matcher over normalized stroke centerlines. Recognition is read-only.
class Strokes {
public:
    explicit Strokes(const std::string& tsv);
    int size() const;
    std::vector<std::string> recognize(const std::vector<std::vector<float>>& strokes, int limit,
                                       bool* strong = nullptr) const;

private:
    struct Model;
    std::shared_ptr<const Model> model_;
};

}  // namespace tujia
