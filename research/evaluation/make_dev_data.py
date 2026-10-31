"""Writes the SYNTHETIC dev dataset used by run_dev_eval.py.

Products, Shona queries, relevance grades and the starter glossary were drafted by
Claude for development testing. Nothing is reviewed by a Shona speaker and nothing
comes from Amazon or Flipkart. The real evaluation (docs/05) needs a licensed
catalogue and reviewed queries/judgments.

Existing files are NOT overwritten (so your corrections survive); pass --force to
regenerate everything.
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent  # research/
FORCE = "--force" in sys.argv

# (id, title, description, brand, category, price, quantity, unit)
CATALOGUE = [
    ("p01", "Iodized Table Salt, 1 kg", "Fine iodized salt for cooking and the table.", "Nyika Foods", "groceries", "$1.20", "1", "kg"),
    ("p02", "Coarse Cooking Salt, 2 kg", "Coarse salt for cooking, curing and preserving meat.", "Nyika Foods", "groceries", "$1.80", "2", "kg"),
    ("p03", "Lavender Bath Salts, 500 g", "Relaxing bath salts with lavender oil for a spa bath at home.", "Kuva Spa", "personal care", "$6.50", "500", "g"),
    ("p04", "White Refined Sugar, 2 kg", "Pure white sugar for tea, baking and cooking.", "Tanda", "groceries", "$2.60", "2", "kg"),
    ("p05", "Brown Sugar, 1 kg", "Unrefined brown sugar with a rich molasses taste.", "Tanda", "groceries", "$1.90", "1", "kg"),
    ("p06", "Sugar-Free Sweetener Tablets, 200 Count", "Zero calorie sweetener tablets for tea and coffee.", "Kuva Health", "groceries", "$3.40", "200", "tablets"),
    ("p07", "Sunflower Cooking Oil, 2 L", "Refined sunflower oil for frying and cooking.", "Greenfield", "groceries", "$4.50", "2", "L"),
    ("p08", "Engine Oil 5W-30, 4 L", "Fully synthetic engine oil for petrol and diesel cars.", "AutoPro", "automotive", "$32.00", "4", "L"),
    ("p09", "Baby Massage Oil, 200 ml", "Gentle almond oil for baby massage and dry skin.", "Kuva Baby", "baby", "$3.90", "200", "ml"),
    ("p10", "Maize Meal, 10 kg", "Roller meal for sadza, made from white maize.", "Chengeta", "groceries", "$9.50", "10", "kg"),
    ("p11", "Hybrid Maize Seed KZ-520, 10 kg", "High yielding white maize seed for rain-fed farming.", "Mhuri Seeds", "farming", "$38.00", "10", "kg"),
    ("p12", "Maize Basal Fertiliser NPK 7:14:7, 50 kg", "Basal fertiliser for maize, applied at planting.", "Mhuri Agri", "farming", "$42.00", "50", "kg"),
    ("p13", "Long Grain Rice, 5 kg", "Polished long grain white rice.", "Zambezi Foods", "groceries", "$7.20", "5", "kg"),
    ("p14", "Full Cream Milk Powder, 400 g", "Instant full cream milk powder. Contains milk.", "Zambezi Dairy", "groceries", "$4.80", "400", "g"),
    ("p15", "Long Life Full Cream Milk, 1 L", "UHT whole milk that needs no fridge until opened.", "Zambezi Dairy", "groceries", "$1.50", "1", "L"),
    ("p16", "Infant Formula Stage 1, 400 g", "Infant milk formula for babies from birth. Contains milk and soy.", "Little Steps", "baby", "$9.90", "400", "g"),
    ("p17", "Black Tea Bags, 100 Count", "Strong black tea bags.", "Nyika Tea", "groceries", "$2.20", "100", "bags"),
    ("p18", "Instant Coffee, 200 g", "Smooth instant coffee granules.", "Kuva Roast", "groceries", "$6.00", "200", "g"),
    ("p19", "Whole Wheat Flour, 2 kg", "Stoneground wholemeal flour for bread and baking. Contains wheat.", "Chengeta", "groceries", "$3.10", "2", "kg"),
    ("p20", "Crunchy Peanut Butter, 400 g", "Peanut butter made from roasted peanuts. Contains peanuts.", "Nyika Foods", "groceries", "$3.30", "400", "g"),
    ("p21", "Sugar Beans, 1 kg", "Dried sugar beans, easy to cook and rich in protein.", "Zambezi Foods", "groceries", "$2.40", "1", "kg"),
    ("p22", "Laundry Washing Powder, 3 kg", "Hand and machine wash powder for clean, fresh clothes.", "BrightWash", "household", "$8.60", "3", "kg"),
    ("p23", "Bath Soap Bar, 3 Pack", "Moisturising bath soap with shea butter.", "Kuva Spa", "personal care", "$2.70", "3", "bars"),
    ("p24", "Dishwashing Liquid, 750 ml", "Lemon dishwashing liquid that cuts through grease.", "BrightWash", "household", "$2.10", "750", "ml"),
    ("p25", "Plastic Water Bucket, 20 L", "Strong plastic bucket with handle and lid for carrying water.", "HomeMate", "household", "$5.40", "20", "L"),
    ("p26", "Stainless Steel Cooking Pot, 5 L", "Heavy base pot with lid for gas and electric stoves.", "HomeMate", "kitchen", "$18.00", "5", "L"),
    ("p27", "Non-Stick Frying Pan, 28 cm", "Non-stick pan for frying eggs, meat and vegetables.", "HomeMate", "kitchen", "$11.50", "28", "cm"),
    ("p28", "Two-Burner Gas Cooker", "Portable two-burner gas stove with auto ignition.", "FlameKing", "kitchen", "$35.00", "1", "unit"),
    ("p29", "Wool Blanket, Double Bed", "Thick warm blanket for cold winter nights.", "Highveld Textiles", "home textiles", "$22.00", "1", "piece"),
    ("p30", "Cotton Bed Sheet Set, Queen", "Four piece sheet set with two pillowcases. Machine washable.", "Highveld Textiles", "home textiles", "$19.00", "4", "pieces"),
    ("p31", "Cotton Bath Towel, Large", "Soft absorbent towel made of 100% cotton.", "Highveld Textiles", "home textiles", "$6.80", "1", "piece"),
    ("p32", "Solar Rechargeable LED Lamp", "Solar powered lamp with USB port to charge phones. Up to 12 hours of light.", "SunBright", "energy", "$14.00", "1", "unit"),
    ("p33", "LED Torch with 3 AA Batteries", "Bright handheld torch, water resistant.", "SunBright", "energy", "$4.90", "1", "unit"),
    ("p34", "AA Alkaline Batteries, 12 Pack", "Long lasting AA batteries for torches, radios and toys.", "PowerCell", "electronics", "$5.60", "12", "pack"),
    ("p35", "Car Battery 12V 60Ah, Model NS60", "Maintenance free car battery with 12 month warranty.", "PowerCell", "automotive", "$85.00", "1", "unit"),
    ("p36", "Samsung Galaxy A15 5G, 128GB", "Smartphone with 6.5 inch display, 50MP camera and 5000mAh battery. Dual SIM.", "Samsung", "electronics", "$210.00", "128", "GB"),
    ("p37", "Silicone Phone Case for Samsung Galaxy A15", "Shockproof soft case with raised edges to protect the screen and camera.", "CaseGuard", "accessories", "$3.50", "1", "unit"),
    ("p38", "USB-C Fast Charger 25W", "Wall charger with USB-C port for phones and tablets. Cable not included.", "VoltEdge", "electronics", "$9.00", "1", "unit"),
    ("p39", "Power Bank 20000 mAh", "Portable charger with two USB ports and a digital display.", "VoltEdge", "electronics", "$24.00", "20000", "mAh"),
    ("p40", "32 Inch LED Television", "HD ready TV with HDMI, USB and built-in decoder.", "Vista", "electronics", "$145.00", "32", "inch"),
    ("p41", "Bluetooth Wireless Headphones", "Over-ear headphones with deep bass and 20 hours of battery life.", "SoundWave", "electronics", "$18.00", "1", "unit"),
    ("p42", "Portable FM Radio with USB", "Rechargeable radio with FM, USB and SD card player.", "SoundWave", "electronics", "$12.00", "1", "unit"),
    ("p43", "Boys School Shoes, Black Leather, Size 5", "Durable lace-up leather school shoes.", "StepUp", "footwear", "$16.00", "1", "pair"),
    ("p44", "Women's Running Shoes, Size 7", "Lightweight breathable sports shoes with cushioned soles.", "StepUp", "footwear", "$28.00", "1", "pair"),
    ("p45", "Men's Cotton T-Shirt, 3 Pack", "Round neck short sleeve t-shirts in assorted colours.", "StepUp Wear", "clothing", "$11.00", "3", "pack"),
    ("p46", "Men's Work Trousers, Navy, Size 34", "Strong cotton work trousers with reinforced pockets.", "StepUp Wear", "clothing", "$14.50", "1", "pair"),
    ("p47", "A4 Exercise Books, 72 Pages, 10 Pack", "Ruled school exercise books with a strong cover.", "Learnwell", "stationery", "$6.00", "10", "pack"),
    ("p48", "Blue Ballpoint Pens, 50 Pack", "Smooth writing blue pens for school and office.", "Learnwell", "stationery", "$4.00", "50", "pack"),
    ("p49", "School Backpack, 25 L", "Padded backpack with laptop pocket and water bottle holder.", "Learnwell", "stationery", "$13.00", "25", "L"),
    ("p50", "Paracetamol Tablets 500 mg, 24 Count", "Pain and fever relief for adults and children over 12.", "Kuva Health", "health", "$1.50", "24", "tablets"),
    ("p51", "Hand Sanitiser, 500 ml", "Alcohol based hand rub that kills 99.9% of germs.", "Kuva Health", "health", "$3.20", "500", "ml"),
    ("p52", "Baby Diapers Size 3, 52 Pack", "Soft absorbent nappies with a wetness indicator.", "Little Steps", "baby", "$14.00", "52", "pack"),
    ("p53", "Steel Garden Hoe", "Heavy duty hoe with wooden handle for weeding and digging.", "Mhuri Agri", "farming", "$7.50", "1", "unit"),
    ("p54", "Layer Chicken Feed, 50 kg", "Complete layer mash for laying hens.", "Mhuri Agri", "farming", "$26.00", "50", "kg"),
]

# (id, shona/english query, type, my_confidence, {product_id: grade 1-3})
# Shona is UNREVIEWED. my_confidence = how sure Claude is that the Shona is right.
QUERIES = [
    ("q01", "munyu", "single", "high", {"p01": 3, "p02": 2}),
    ("q02", "shuga", "single", "high", {"p04": 3, "p05": 2, "p06": 1}),
    ("q03", "sukari", "single", "high", {"p04": 3, "p05": 2, "p06": 1}),
    ("q04", "mafuta ekubika", "multiword", "high", {"p07": 3}),
    ("q05", "mafuta", "ambiguous", "high", {"p07": 2, "p08": 1, "p09": 1}),
    ("q06", "chibage", "single", "high", {"p10": 2, "p11": 2, "p12": 1}),
    ("q07", "upfu hwechibage", "multiword", "medium", {"p10": 3}),
    ("q08", "hupfu hwechibage", "variant", "medium", {"p10": 3}),
    ("q09", "mupunga", "single", "high", {"p13": 3}),
    ("q10", "mukaka", "single", "high", {"p14": 3, "p15": 3, "p16": 1}),
    ("q11", "mukaka wemwana", "multiword", "medium", {"p16": 3, "p14": 1}),
    ("q12", "tii", "single", "high", {"p17": 3}),
    ("q13", "kofi", "single", "high", {"p18": 3}),
    ("q14", "nzungu", "single", "medium", {"p20": 2}),
    ("q15", "bhinzi", "single", "medium", {"p21": 3}),
    ("q16", "sipo", "ambiguous", "high", {"p23": 3, "p24": 2, "p22": 2}),
    ("q17", "hari", "single", "medium", {"p26": 3, "p27": 1}),
    ("q18", "bhaketi", "single", "medium", {"p25": 3}),
    ("q19", "gumbeze", "single", "high", {"p29": 3, "p30": 1}),
    ("q20", "foni", "single", "high", {"p36": 3, "p37": 1, "p38": 1}),
    ("q21", "chaja", "single", "medium", {"p38": 3, "p39": 2}),
    ("q22", "redhiyo", "single", "high", {"p42": 3}),
    ("q23", "terevhizheni", "single", "medium", {"p40": 3}),
    ("q24", "shangu", "single", "high", {"p43": 3, "p44": 3}),
    ("q25", "hembe", "single", "medium", {"p45": 3}),
    ("q26", "mabhuku", "single", "medium", {"p47": 3}),
    ("q27", "badza", "single", "high", {"p53": 3}),
    ("q28", "bhatiri", "single", "medium", {"p34": 3, "p35": 2}),
    ("q29", "tauro", "single", "medium", {"p31": 3}),
    ("q30", "mwenje wezuva", "multiword", "low", {"p32": 3, "p33": 1}),
    ("q31", "mushonga wekurwadza musoro", "multiword", "medium", {"p50": 3}),
    ("q32", "mushonga wekuchenesa maoko", "multiword", "medium", {"p51": 3}),
    ("q33", "sipo yekusuka mbatya", "multiword", "medium", {"p22": 3, "p23": 1}),
    ("q34", "mafuta emotokari", "multiword", "medium", {"p08": 3}),
    ("q35", "bhatiri remotokari", "multiword", "medium", {"p35": 3, "p34": 1}),
    ("q36", "mbeu yechibage", "multiword", "medium", {"p11": 3}),
    ("q37", "fetiraiza yechibage", "multiword", "medium", {"p12": 3, "p11": 1}),
    ("q38", "chikafu chehuku", "multiword", "medium", {"p54": 3}),
    ("q39", "shangu dzechikoro", "multiword", "medium", {"p43": 3}),
    ("q40", "bhegi rechikoro", "multiword", "medium", {"p49": 3}),
    ("q41", "munyu we1kg", "attribute", "medium", {"p01": 3, "p02": 1}),
    ("q42", "shuga 2kg", "attribute", "high", {"p04": 3, "p05": 1}),
    ("q43", "mafuta ekubika 2 litres", "attribute", "high", {"p07": 3}),
    ("q44", "upfu hwechibage 10kg", "attribute", "medium", {"p10": 3}),
    ("q45", "foni yeSamsung", "brand_mix", "medium", {"p36": 3, "p37": 1, "p38": 1}),
    ("q46", "case yeSamsung A15", "brand_mix", "low", {"p37": 3, "p36": 1}),
    ("q47", "pampers", "brand_mix", "medium", {"p52": 3}),
    ("q48", "mafuta ekubka", "typo", "high", {"p07": 3}),
    ("q49", "MAFUTA EKUBIKA", "caps", "high", {"p07": 3}),
    ("q50", "cooking oil", "english", "high", {"p07": 3}),
    ("q51", "phone charger", "english", "high", {"p38": 3, "p39": 2}),
    ("q52", "school shoes", "english", "high", {"p43": 3}),
    ("q53", "sugar beans", "english", "high", {"p21": 3}),
]

# (shona, english, domain). Status is "draft": NOT approved by a reviewer.
GLOSSARY = [
    ("munyu", "salt", "food"), ("shuga", "sugar", "food"), ("sukari", "sugar", "food"),
    ("mafuta ekubika", "cooking oil", "food"), ("mafuta", "oil", "general"),
    ("chibage", "maize", "food"), ("upfu", "flour meal", "food"), ("hupfu", "flour meal", "food"),
    ("mupunga", "rice", "food"), ("mukaka", "milk", "food"), ("mwana", "baby", "general"),
    ("tii", "tea", "food"), ("kofi", "coffee", "food"), ("nzungu", "peanut", "food"),
    ("bhinzi", "beans", "food"), ("huku", "chicken", "farming"), ("chikafu", "feed food", "farming"),
    ("mbeu", "seed", "farming"), ("fetiraiza", "fertiliser", "farming"), ("badza", "hoe", "farming"),
    ("sipo", "soap", "household"), ("kusuka", "wash", "household"), ("mbatya", "clothes", "household"),
    ("hari", "pot", "household"), ("bhaketi", "bucket", "household"), ("gumbeze", "blanket", "household"),
    ("tauro", "towel", "household"), ("mwenje", "lamp light", "household"), ("zuva", "solar sun", "household"),
    ("foni", "phone", "electronics"), ("chaja", "charger", "electronics"), ("redhiyo", "radio", "electronics"),
    ("terevhizheni", "television", "electronics"), ("bhatiri", "battery", "electronics"),
    ("motokari", "car", "automotive"), ("shangu", "shoes", "clothing"), ("hembe", "shirt", "clothing"),
    ("chikoro", "school", "education"), ("bhuku", "book exercise", "education"), ("bhegi", "bag backpack", "education"),
    ("mushonga", "medicine", "health"), ("kurwadza", "pain", "health"), ("musoro", "head", "health"),
    ("kuchenesa", "clean", "health"), ("maoko", "hands", "health"), ("pampers", "diapers nappies", "baby"),
]

# (id, type, safety_critical, english text)
TRANSLATION_CASES = [
    ("t01", "title", False, "Tastic Long Grain Parboiled Rice, 2 kg - Pack of 2, Model TG-2000"),
    ("t02", "title", False, "Samsung Galaxy A15 5G, 128GB, Dual SIM, Model SM-A156B"),
    ("t03", "title", False, "Sunflower Cooking Oil, 2 L, Pack of 6"),
    ("t04", "title", False, "Men's Cotton T-Shirt, 3 Pack, Size L, Assorted Colours"),
    ("t05", "title", False, "Stainless Steel Pressure Cooker 5 Litre with Safety Valve"),
    ("t06", "title", False, "Boys School Shoes, Black Leather, Size 5"),
    ("b01", "bullet", False, "Made from 100% natural ingredients with no added preservatives."),
    ("b02", "bullet", False, "Fits most 6.5 inch smartphones. Easy to clean."),
    ("b03", "bullet", False, "12 month warranty. Keep your receipt as proof of purchase."),
    ("b04", "bullet", False, "Machine washable at 30°C. Do not bleach. Tumble dry low."),
    ("b05", "bullet", True, "Suitable for children aged 3 years and above."),
    ("b06", "bullet", False, "Battery lasts up to 20 hours on a single charge."),
    ("d01", "description", False, "This iodized table salt is ideal for everyday cooking. It is finely ground and fortified with iodine. Store in a cool, dry place."),
    ("d02", "description", False, "This smartphone has a 6.5 inch display and a 50MP camera. The 5000mAh battery lasts all day. It supports dual SIM cards and 5G networks."),
    ("d03", "description", False, "Our roller meal is milled from the finest white maize. It cooks into smooth, fluffy sadza every time. Store in a cool, dry place and use within 6 months of opening."),
    ("d04", "description", False, "Stay warm this winter with our thick wool blanket. The soft fabric is gentle on the skin. Available in double bed size only."),
    ("d05", "description", True, "Apply 400 kg per hectare at planting. Place the fertiliser in the planting hole and cover with soil. Keep away from children and animals. Wash hands after use."),
    ("d06", "description", False, "Charge in direct sunlight for 8 hours. The lamp gives up to 12 hours of bright light. It can also charge your phone through the USB port."),
    ("p01", "price", False, "Price: $2.99 (was $4.50). Save 33%."),
    ("p02", "price", False, "Buy 2 get 1 free. Offer ends 31 October."),
    ("p03", "price", False, "Free delivery on orders over $50."),
    ("p04", "price", False, "Only 3 left in stock - order soon."),
    ("p05", "price", False, "Delivery in 5-7 working days."),
    ("p06", "price", False, "Limited time deal: 25% off today only."),
    ("p07", "price", False, "Save 20% when you subscribe."),
    ("s01", "safety", True, "WARNING: Contains peanuts and tree nuts. Keep out of reach of children."),
    ("s02", "safety", True, "Allergen information: Contains wheat, milk, eggs and soy. May contain traces of nuts."),
    ("s03", "safety", True, "Do not exceed the stated dose. If symptoms persist, consult your doctor."),
    ("s04", "safety", True, "Keep away from fire. Do not use near open flames."),
    ("s05", "safety", True, "Not suitable for children under 3 years. Small parts may be a choking hazard."),
    ("s06", "safety", True, "Store below 25°C. Once opened, keep refrigerated and consume within 3 days."),
    ("s07", "safety", True, "For external use only. Avoid contact with eyes."),
    ("s08", "safety", True, "Always follow the preparation instructions on the pack. Use boiled water cooled to 70°C."),
    ("r01", "policy", False, "Returns accepted within 30 days of delivery. Items must be unused and in original packaging."),
    ("x01", "tricky", False, "Maize meal, 10 kg bag, 100% natural"),
    ("x02", "tricky", False, "Nike Air Max 90, Model CN8490-100, Size 10"),
    ("x03", "tricky", False, "Dettol Antiseptic Liquid 500ml, kills 99.9% of germs"),
    ("x04", "tricky", False, "Samsung 55 inch 4K UHD Smart TV, UA55AU7000"),
    ("x05", "tricky", False, "Bundle includes charger, cable and case. Battery not included."),
    ("x06", "tricky", False, "Colour: Navy Blue | Size: Medium | Material: 100% Cotton"),
]

NOTE = """# DEV data note

SYNTHETIC development data. Products, Shona queries, relevance grades and the starter
glossary were drafted by Claude. NOT reviewed by a Shona speaker. NOT from Amazon or
Flipkart. Do not use for the final evaluation in docs/05, which needs a licensed
catalogue, reviewed queries and separate judgment files. Generated by
research/evaluation/make_dev_data.py.
"""


def write(path: Path, rows, label: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and not FORCE:
        print(f"skip   {path.relative_to(ROOT.parent)} (exists; use --force to overwrite)")
        return
    path.write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8"
    )
    print(f"wrote  {path.relative_to(ROOT.parent)} ({len(rows)} {label})")


write(ROOT / "data/catalogue/dev_catalogue.jsonl", [
    {"id": i, "title": t, "description": d, "brand": b, "category": c, "price": p,
     "currency": "USD", "quantity": q, "unit": u, "source": "synthetic-dev"}
    for i, t, d, b, c, p, q, u in CATALOGUE], "products")

write(ROOT / "data/queries/dev_queries.jsonl", [
    {"queryId": i, "query": q, "type": ty, "my_confidence": cf, "relevance": rel,
     "annotator": "claude-draft", "status": "unreviewed"}
    for i, q, ty, cf, rel in QUERIES], "queries")

write(ROOT / "data/glossary/dev_glossary_starter.jsonl", [
    {"english": en, "shona": sn, "domain": dm, "reviewer": "unreviewed", "status": "draft"}
    for sn, en, dm in GLOSSARY], "terms")

write(HERE / "dev_translation_cases.jsonl", [
    {"id": i, "type": ty, "safety_critical": sc, "text": tx}
    for i, ty, sc, tx in TRANSLATION_CASES], "cases")

note = ROOT / "data/DEV_DATA_NOTE.md"
if FORCE or not note.exists():
    note.write_text(NOTE, encoding="utf-8")
