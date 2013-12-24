var app = angular.module('app', []);

app.factory('ChatService', function() {
  var service = {};

  service.connect = function() {
    if(service.ws) { return; }

    var ws = new WebSocket("ws://localhost:9000/websocket/");

    ws.onopen = function() {
      service.callback("Succeeded to open a connection");
    };

    ws.onerror = function() {
      service.callback("Failed to open a connection");
    }

    ws.onmessage = function(message) {
      service.callback(message.data);
    };

    service.ws = ws;
  }

  service.send = function(message) {
    service.ws.send(JSON.stringify(message));
  }

  service.subscribe = function(callback) {
    service.callback = callback;
  }

  return service;
});


function AppCtrl($scope, ChatService) {
  $scope.messages = [];

  ChatService.subscribe(function(message) {
    $scope.messages.push(message);
    $scope.$apply();
  });

  $scope.connect = function() {
    ChatService.connect();
    $http.get(jsRoutes.controllers.AppController.start().url)
  }

  $scope.send = function() {
    ChatService.send( {topic:"foobar", msg:$scope.text} );
    $scope.text = "";
  }
}