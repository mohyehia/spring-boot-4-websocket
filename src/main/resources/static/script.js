let stompClient = null;

$(document).ready(function () {
  console.log("Index page is loaded!");
  connect();

  $("#send").click(function () {
    sendPrivateMessage();
  });

  $("#sendLarge").click(function () {
    sendLargeMessage();
  });

  $("#sendMultiple").click(function () {
    sendMultipleMessages();
  });

  function connect() {
    // connect to the endpoint created in WebSocketConfig
    const socket = new WebSocket("http://localhost:8080/ws");
    stompClient = Stomp.over(socket);
    stompClient.connect(
      {
        Authorization:
          "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJtb2giLCJuYW1lIjoiTW9oYW1tZWQiLCJpYXQiOjE1MTYyMzkwMjIsImV4cCI6MTc4NDkzNzYwMCwicm9sZXMiOlsiVVNFUiJdfQ.Qq_RfJwfxZC0VHhkkS5qNw7w1BuHV1FM6WrlVrX7PaI",
      },
      function (frame) {
        console.log("Connected to: " + frame);

        stompClient.subscribe("/user/queue/notifications", function (message) {
          console.log("Received message back from the server");
          // console.log(JSON.parse(message.body));
          console.log(message.body);
          const toastElement = document.getElementById("private-toast");
          const toastBootstrap =
            bootstrap.Toast.getOrCreateInstance(toastElement);
          const spanElement = document.getElementById("toast-content");
          spanElement.textContent = message.body;
          toastBootstrap.show();
        });
        let count = 1;
        stompClient.subscribe("/topic/general-updates", function (message) {
          console.log("Received general updates from the server!");
          console.log(message.body);
          $("#general-updates").append(`<tr>
          <th scope="row">${count++}</th>
          <td>${message.body}</td>
        </tr>`);
        });

        // subscribe to the error channel
        stompClient.subscribe("/user/queue/errors", function (message) {
          var error = JSON.parse(message.body);
          console.error(
            "[" + error.code + "] " + error.message + " at " + error.timestamp,
          );
          const toastElement = document.getElementById("errorToast");
          const toastBootstrap =
            bootstrap.Toast.getOrCreateInstance(toastElement);
          const spanElement = document.getElementById("toast-error");
          spanElement.textContent = error.message;

          const statusSpanElement = document.getElementById("status-code");
          statusSpanElement.textContent = error.code;
          toastBootstrap.show();
        });
      },
      function (errorFrame) {
        console.error("STOMP error from server:", errorFrame);
      },
    );
  }

  function sendPrivateMessage() {
    console.log("Sending private message!");
    stompClient.send("/app/notification", {}, $("#private-message").val());
  }

  function sendLargeMessage() {
    console.log("Sending large message!");
    var largeMsg = "X".repeat(65 * 1024);
    stompClient.send("/app/notification", {}, largeMsg);
  }

  function sendMultipleMessages() {
    for (var i = 0; i < 10; i++) {
      stompClient.send("/app/notification", {}, "Sample message");
    }
  }
});
