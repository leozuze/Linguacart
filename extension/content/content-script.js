console.log("LinguaCart content script is running");

function getProductInfo() {
  const titleEl = document.querySelector("#productTitle");
  const priceEl = document.querySelector(".a-price .a-offscreen");
  return {
    title: titleEl ? titleEl.textContent.trim() : null,
    price: priceEl ? priceEl.textContent.trim() : null,
  };
}

function getListingItems() {
  const results = document.querySelectorAll(
    'div[data-component-type="s-search-result"]'
  );
  return Array.from(results).map((item) => {
    const titleEl = item.querySelector("h2 span");
    const priceEl = item.querySelector(".a-price .a-offscreen");
    const price = priceEl ? priceEl.textContent.trim() : null;
    return {
      asin: item.getAttribute("data-asin"),
      title: titleEl
        ? titleEl.textContent.replace(/\s+/g, " ").trim()
        : null,
      price,
      note: price
        ? ""
        : item.innerText.replace(/\s+/g, " ").slice(-120),
    };
  });
}

function run() {
  if (location.pathname.includes("/dp/")) {
    console.log("LinguaCart product page:", getProductInfo());
  } else {
    console.table(getListingItems());
  }
}

// Amazon loads content after the first render, so wait briefly for now
setTimeout(run, 2000);