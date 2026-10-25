#include "engine.h"
#include <chrono>
#include <iostream>
#include <thread>
#include <stdexcept>
#include <unistd.h>
#include <sys/syscall.h>

int main(int argc, char ** argv) {
    if (argc < 3) return 2;
    zinna::Zenzai engine(argv[1]);
    if (std::string(argv[2]) == "--ttt-test") {
        const std::string prefix = u8"\uEE00ムソウカキョウ\uEE01";
        const std::string target = u8"夢想夏郷";
        const auto baseline = engine.generate(prefix, 192, zinna::cancel_generation());
        zinna::Zenzai trainer(argv[1]);
        auto version = trainer.export_version();
        engine.import_version(version);
        zinna::Zenzai::free_version(version);
        int before = syscall(__NR_memfd_create, "before", 1);
        int after = syscall(__NR_memfd_create, "after", 1);
        if (before < 0 || after < 0) return 8;
        trainer.snapshot(before, 0);
        auto started = std::chrono::steady_clock::now();
        trainer.train(prefix + target);
        std::cout << "normal_ttt_ms=" << std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now()-started).count() << '\n';
        trainer.snapshot(after, 0);
        bool changed = false;
        char a[65536], b[65536];
        while (true) {
            auto n = read(before, a, sizeof(a));
            auto m = read(after, b, sizeof(b));
            if (n != m) return 9;
            if (n <= 0) break;
            if (std::string(a, n) != std::string(b, m)) changed = true;
        }
        if (!changed) return 10;
        if (engine.generate(prefix, 192, zinna::cancel_generation()) != baseline) return 11;
        int steps = trainer.learn_word(prefix, target);
        if (steps == 0) return 12;
        version = trainer.export_version();
        engine.import_version(version);
        zinna::Zenzai::free_version(version);
        if (engine.generate(prefix, 192, zinna::cancel_generation()) != target) return 13;
        trainer.snapshot(after, 17);
        zinna::Zenzai restored(argv[1]);
        if (restored.restore(after) != 17) return 14;
        if (restored.generate(prefix, 192, zinna::cancel_generation()) != target) return 15;
        if (ftruncate(after, 100) != 0) return 16;
        bool rejected = false;
        try { restored.restore(after); } catch (const std::exception &) { rejected = true; }
        if (!rejected || restored.generate(prefix, 192, zinna::cancel_generation()) != target) return 17;
        close(before); close(after);
        std::cout << "PASS: actual TTT weight changes, frozen inference, word learning, publication, memory snapshot restore, corrupt snapshot rejection\n";
        return 0;
    }
    if (std::string(argv[2]) == "--self-test") {
        const std::string prompt = u8"\uEE00キョウハイイテンキデス\uEE01";
        auto generation = zinna::cancel_generation();
        zinna::cancel_generation();
        if (!engine.generate(prompt, 192, generation).empty()) return 3;
        auto result = engine.generate(prompt, 192, zinna::cancel_generation());
        if (result != u8"今日はいい天気です") return 4;
        std::string long_prompt = u8"\uEE00";
        for (int i = 0; i < 10; ++i) long_prompt += u8"キョウハイイテンキデス";
        long_prompt += u8"\uEE01";
        generation = zinna::cancel_generation();
        std::string cancelled;
        std::thread decoding([&] { cancelled = engine.generate(long_prompt, 192, generation); });
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
        zinna::cancel_generation();
        decoding.join();
        if (!cancelled.empty()) return 5;
        result = engine.generate(u8"\uEE00サイキドウ\uEE01", 192, zinna::cancel_generation());
        if (result != u8"再起動") return 6;
        if (!engine.generate(std::string(600, 'a'), 192, zinna::cancel_generation()).empty()) return 7;
        std::cout << "PASS: generation, stale requests, active cancellation, reuse, context limit\n";
        return 0;
    }
    auto start = std::chrono::steady_clock::now();
    std::string result = engine.generate(argv[2], 192, zinna::cancel_generation());
    std::cout << result << '\n';
    std::cerr << "generation_ms=" << std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count() << '\n';
    return result.empty() ? 1 : 0;
}
