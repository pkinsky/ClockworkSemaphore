var app = angular.module('app', []);

app.factory('ChatService', function() {
  var service = {};

  service.connect = function() {
    if(service.ws) { return; }

    var ws = new WebSocket("ws://localhost:9000/websocket/");
    ws.onopen = function() {
      //service.callback("Succeeded to open a connection");
      //alert("connection open");
    };

    ws.onerror = function() {
      //service.callback("Failed to open a connection");
      alert("error, failed to open connection");
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

  $scope.trending = [];

  ChatService.connect();

  ChatService.subscribe(function(message) {
    var actual = jQuery.parseJSON(message)

    if ('msg' in actual){
        $scope.messages.push((actual['msg']));
    }

    if ('trending' in actual){
         $scope.trending = actual['trending'];
    }

    $scope.$apply();
  });

  $scope.connect = function() {
    ChatService.connect();
  }

  $scope.send = function() {
    var topics = $scope.text.split(" ");
    topics = jQuery.grep(topics, function( a ) {
              return a.charAt(0) === '#';
            });

    topics = $.map(topics, function( n ) {
               return n.substring(1);
             });


    ChatService.send( {topic:topics, msg:$scope.text} );
    $scope.text = "";
  }
}