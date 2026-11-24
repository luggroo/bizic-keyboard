#pragma once
#include <string>

namespace tujia {
// Android and Apple text APIs identify editor positions using UTF-16.
std::string suffixAfterChoice(const std::string& draft, int consumedUTF16);
std::string spaceWord(const std::string& candidate, char16_t before, char16_t after);
}
