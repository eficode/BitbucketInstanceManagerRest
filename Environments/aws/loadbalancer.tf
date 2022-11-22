

resource "aws_lb" "load-balancer" {

  name                       = "${var.tags.useCase}-${var.tags.owner}-lb"
  internal                   = false
  load_balancer_type         = "network"
  subnets                    = aws_subnet.base-stack-public-subnet[*].id
  enable_deletion_protection = false



}

resource "aws_lb_listener" "lb-listener" {
  count = length(var.ingress_rules_from_trusted)
  load_balancer_arn = aws_lb.load-balancer.arn
  port = var.ingress_rules_from_trusted[count.index].port
  protocol = upper(var.ingress_rules_from_trusted[count.index].protocol)


  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.target-group[count.index].arn
  }

}

resource "aws_lb_target_group" "target-group" {
  count = length(var.ingress_rules_from_trusted)

  name        = "${var.tags.useCase}-${var.tags.owner}-port-${var.ingress_rules_from_trusted[count.index].port}"
  port        = var.ingress_rules_from_trusted[count.index].port
  protocol    = upper(var.ingress_rules_from_trusted[count.index].protocol)
  target_type = "instance"
  vpc_id      = aws_vpc.base-stack.id


}

resource "aws_lb_target_group_attachment" "lb-target" {

  count = length(var.ingress_rules_from_trusted)

  target_group_arn = aws_lb_target_group.target-group[count.index].arn
  target_id        = aws_instance.ec2-node.id
  port             = var.ingress_rules_from_trusted[count.index].port


}