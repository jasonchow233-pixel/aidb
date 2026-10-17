import { createApp } from "vue";
import App from "./App.vue";
import "./styles/tokens.css";
import "./styles/app.css";

document.documentElement.dataset.theme =
  (localStorage.getItem("adb.theme") as "dark" | "light") ?? "dark";

createApp(App).mount("#app");
