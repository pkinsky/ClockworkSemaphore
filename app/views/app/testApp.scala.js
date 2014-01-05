@(id: securesocial.core.Identity, alias: Option[String])(implicit r: RequestHeader)

@import service.RedisUserService.uidFromIdentityId

/*fixed navbar offset

$(window).resize(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});

$(window).load(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});
*/

var app = angular.module('app', []);

app.factory('ChatService', function() {
  var service = {};

  service.connect = function() {
    if(service.ws) { return; }

    var ws = new WebSocket("@routes.AppController.indexWS.webSocketURL()");
    ws.onopen = function() {
      service.ws.send(JSON.stringify("ACK"));
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

  $scope.user_id = "@{uidFromIdentityId(id.identityId)}";

  //no alias is empty string

  $scope.avatars = {}
  $scope.avatars[$scope.user_id] = "@{id.avatarUrl.getOrElse("")}";




  $scope.alias = "@{alias.getOrElse("")}";

  $scope.signup_complete = function () {
    return $scope.alias.length != 0;
  };

  //init via jquery
  $scope.aliases = {};
  if ($scope.signup_complete()){
    $scope.aliases[$scope.user_id] = $scope.alias;
  }






  $scope.messages = [];




  $scope.set_alias = function() {
    var alias_in = $("#alias").val();
    console.log("setting alias: " + alias_in);
    ChatService.send( {user_id:$scope.user_id, alias:alias_in} );
  };




    $scope.activeFilter = function (message) {
        return !_.some(message.topics, function(topic){
                return !$scope.activeTopics[topic]
            }
        );
    };




  $scope.activeTopics = {};

  $scope.trending = [];

  ChatService.connect();

  ChatService.subscribe(function(message) {
    
    console.log("msg: " + message);
    
    var actual = jQuery.parseJSON(message)

    if ('msg' in actual){
        var msg = actual['msg']
        msg['show'] = true; //will show messages pushed to ignored topics
        $scope.messages.push((msg));
    }

    if ('trending' in actual){
        var trending = actual['trending']
        trending.forEach(function(t) {
            if (t.name in $scope.activeTopics){
            } else {
               $scope.activeTopics[t.name] = true;
            }
          });


        $scope.trending = trending;
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
  });

  $scope.connect = function() {
    ChatService.connect();
  }

  $scope.toggleActive = function(trend){
    $scope.activeTopics[trend.name] = ! $scope.activeTopics[trend.name]
  }


  $scope.send = function() {
    var text = $("#tweeter").val();

    var topics = text.split(" ");
    topics = jQuery.grep(topics, function( a ) {
              return a.charAt(0) === '#';
            });

    topics = $.map(topics, function( n ) {
               return n.substring(1);
             });


    ChatService.send( {topic:topics, msg:text} );

    $("#tweeter").val("");
  }
}