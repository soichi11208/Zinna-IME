#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

namespace zinna {
// Cancellation has no model pointers, so the UI can cancel safely during loading or teardown.
int64_t cancel_generation();
class Zenzai {
public:
    explicit Zenzai(const std::string & path);
    ~Zenzai();
    std::string generate(const std::string & prompt, int max_tokens, int64_t generation);
    void train(const std::string & document);
    int learn_word(const std::string & prefix, const std::string & output);
    int64_t export_version();
    void import_version(int64_t version);
    static void free_version(int64_t version);
    void snapshot(int fd, uint64_t cursor);
    uint64_t restore(int fd);
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
}
