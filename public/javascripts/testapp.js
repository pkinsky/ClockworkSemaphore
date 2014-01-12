/*dynamic navbar offset

$(window).resize(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});

$(window).load(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});
*/

var app = angular.module('app', []);

app.factory('ChatService', function() {

   console.log("spinning up ChatService")

  var service = {};

  service.connect = function() {
    if(service.ws) { return; }

    var ws = new WebSocket("ws://localhost:9000/websocket/");

    ws.onopen = function() {
        console.log("ack websocket");
      service.ws.send(JSON.stringify("ACK"));
    };

    ws.onerror = function() {
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
  ChatService.connect();

  //map of user_id => object describing public components of user
  $scope.users = {};

  $scope.signup_complete = function () {
    return $scope.current_user.alias.length != 0;
  };


  $scope.messages = [];

    //no error when commented out..?

  $scope.set_alias = function() {
    console.log("TEST TEST TEST");
    var alias_in = $("#alias").val();
    console.log("setting alias: " + alias_in);
    ChatService.send( {user_id:$scope.user_id, alias:alias_in} );
  };


  ChatService.subscribe(function(message) {
            console.log("msg: " + message);
            var actual = jQuery.parseJSON(message)

            if ('msg' in actual){
                var msg = actual['msg']
                msg['show'] = true; //will show messages pushed to ignored topics
                $scope.messages.push((msg));
            }

            if ('user_info' in actual){
                //add new users to $scope.users
            }

            if ('alias_result' in actual){
                if (actual['alias_result']['pass']){
                    $scope.alias = actual['alias_result']['alias'];
                    $scope.aliases[actual['alias_result']['user_id']] = actual['alias_result']['alias'];
                } else {
                    alert("alias taken: " +  actual['alias_result']['alias']);
                }
            }

            $scope.$apply();
      }
  );

  $scope.connect = function() {
    ChatService.connect();
  };

  $scope.send = function() {
    var text = $("#tweeter").val();
	if (text.length > 0){
		ChatService.send( {msg:text} );
		$("#tweeter").val("");
	}
  };

}