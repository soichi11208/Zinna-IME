#include "engine.h"
#include "llama.h"
#include <algorithm>
#include <chrono>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>
#include <unistd.h>

extern "C" int32_t llama_ttt_save_fd(llama_context *, int, uint64_t);

namespace zinna {
namespace {
std::atomic<int64_t> current_generation{0};
std::once_flag backend_once;
struct Abort {
    int64_t generation;
    std::chrono::steady_clock::time_point deadline;
    static bool check(void * data) {
        auto & a = *static_cast<Abort *>(data);
        return current_generation.load() != a.generation ||
               std::chrono::steady_clock::now() >= a.deadline;
    }
};
}
int64_t cancel_generation() { return ++current_generation; }

struct Zenzai::Impl {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    ~Impl() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

Zenzai::Zenzai(const std::string & path) : impl(std::make_unique<Impl>()) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;
    impl->model = llama_model_load_from_file(path.c_str(), mp);
    if (!impl->model) throw std::runtime_error("Cannot load Zenzai model");
    auto cp = llama_context_default_params();
    cp.n_ctx = 512;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    cp.n_threads_batch = cp.n_threads;
    impl->context = llama_init_from_model(impl->model, cp);
    if (!impl->context) throw std::runtime_error("Cannot create Zenzai context");
}
Zenzai::~Zenzai() = default;

namespace {
void ttt_check(int status) {
    if (status < 0) throw std::runtime_error(llama_ttt_last_error());
}
}
void Zenzai::train(const std::string & document) {
    ttt_check(llama_ttt_apply(impl->context, document.c_str(), 1e-4f));
}
int Zenzai::learn_word(const std::string & prefix, const std::string & output) {
    int32_t steps = 0;
    int result = llama_ttt_learn_word(impl->context, prefix.c_str(), output.c_str(), 32, &steps);
    ttt_check(result);
    return result == 1 ? steps + 1 : 0;
}
int64_t Zenzai::export_version() {
    auto * version = llama_ttt_export_version(impl->context);
    if (!version) throw std::runtime_error(llama_ttt_last_error());
    return reinterpret_cast<int64_t>(version);
}
void Zenzai::import_version(int64_t version) {
    ttt_check(llama_ttt_import_version(impl->context, reinterpret_cast<llama_ttt_version *>(version)));
}
void Zenzai::free_version(int64_t version) {
    llama_ttt_free_version(reinterpret_cast<llama_ttt_version *>(version));
}
void Zenzai::snapshot(int fd, uint64_t cursor) {
    if (lseek(fd, 0, SEEK_SET) < 0 || ftruncate(fd, 0) != 0) throw std::runtime_error("Cannot reset snapshot memory");
    ttt_check(llama_ttt_save_fd(impl->context, fd, cursor));
    if (lseek(fd, 0, SEEK_SET) < 0) throw std::runtime_error("Cannot rewind snapshot memory");
}
uint64_t Zenzai::restore(int fd) {
    uint64_t cursor = 0;
    auto path = "/proc/self/fd/" + std::to_string(fd);
    ttt_check(llama_ttt_load(impl->context, path.c_str(), &cursor));
    return cursor;
}

std::string Zenzai::generate(const std::string & prompt, int max_tokens, int64_t generation) {
    Abort abort{generation, std::chrono::steady_clock::now() + std::chrono::seconds(5)};
    if (Abort::check(&abort)) return {};
    auto * ctx = impl->context;
    const auto * vocab = llama_model_get_vocab(impl->model);
    std::vector<llama_token> tokens(prompt.size() + 8);
    // The supplied fine-tuning pipeline uses the v2 tags without an added BOS token.
    int n = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), false, true);
    if (n <= 0 || n + max_tokens >= 512) return {};
    tokens.resize(n);
    llama_kv_cache_clear(ctx);
    llama_set_abort_callback(ctx, Abort::check, &abort);
    struct ResetAbort {
        llama_context * ctx;
        ~ResetAbort() { llama_set_abort_callback(ctx, nullptr, nullptr); }
    } reset{ctx};
    if (llama_decode(ctx, llama_batch_get_one(tokens.data(), n)) != 0) return {};
    std::string output;
    for (int i = 0; i < max_tokens; ++i) {
        if (Abort::check(&abort)) return {};
        const float * logits = llama_get_logits_ith(ctx, -1);
        if (!logits) return {};
        int count = llama_vocab_n_tokens(vocab);
        llama_token token = std::max_element(logits, logits + count) - logits;
        if (llama_vocab_is_eog(vocab, token)) return output;
        char piece[128];
        int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (size <= 0 || size > static_cast<int>(sizeof(piece))) return {};
        output.append(piece, size);
        if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) return {};
    }
    // Never offer an unfinished conversion when decoding exceeds the output budget.
    return {};
}
}
