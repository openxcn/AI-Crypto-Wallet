from pathlib import Path
p = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android/write_test_output.txt")
print("writing", p)
p.write_text("hello world\n", encoding="utf-8")
print("done")
