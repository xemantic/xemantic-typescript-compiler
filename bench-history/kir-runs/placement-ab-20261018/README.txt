run  placement(tsgo)  
A1 os scheduler kir: os scheduler equiv: {'mitt': 'agree', 'toml': 'agree'}
A2 os scheduler kir: os scheduler equiv: {'mitt': 'agree', 'toml': 'agree'}
B1 taskset -c 3 kir: os scheduler equiv: {'mitt': 'agree', 'toml': 'agree'}
B2 taskset -c 3 kir: os scheduler equiv: {'mitt': 'agree', 'toml': 'agree'}
lib/arm                          A1                     A2                     B1                     B2
mitt/kir          293 cv 1.4% r  11      301 cv 2.7% r  20      298 cv25.5% r 224      307 cv30.2% r 279
mitt/tsgo         343 cv 2.2% r  21      354 cv 1.7% r  16      354 cv 1.1% r  11      348 cv 3.2% r  34
mitt/xtsc         345 cv 2.3% r  24      344 cv 2.7% r  25      349 cv 1.8% r  20      349 cv 0.8% r   7
toml/kir          686 cv 0.7% r  11      691 cv 1.6% r  32      692 cv 0.7% r  15      694 cv 0.8% r  16
toml/tsgo         459 cv 1.4% r  16      471 cv 0.9% r  11      466 cv 2.1% r  28      472 cv 1.0% r  11
toml/xtsc         476 cv 4.2% r  58      479 cv 4.1% r  57      475 cv 2.3% r  33      475 cv 1.4% r  17
