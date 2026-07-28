// Throwaway: does the bundled model actually load and convert through Backend::from_paths?
use std::time::Instant;
use karukan_engine::{Backend, KanaKanjiConverter};

fn main() {
    let dir = "/mnt/ssd4/ime/third_party/karukan-model";
    let gguf = format!("{dir}/jinen-v1-xsmall-Q5_K_M.gguf");
    let tok = format!("{dir}/tokenizer.json");

    let t = Instant::now();
    let conv = match KanaKanjiConverter::new(Backend::from_paths(&gguf, &tok)) {
        Ok(c) => c,
        Err(e) => { eprintln!("load failed: {e}"); std::process::exit(1); }
    };
    println!("  load: {:?}\n", t.elapsed());

    for reading in ["にほんご", "かんじ", "でんわばんごう", "きょうはいいてんきですね", "とうきょうとっきょきょかきょく"] {
        let t = Instant::now();
        match conv.convert(reading, "", 3) {
            Ok(c) => println!("  {:<32} {:>7.0?}  {:?}", reading, t.elapsed(), c),
            Err(e) => println!("  {:<32} error: {e}", reading),
        }
    }
}
